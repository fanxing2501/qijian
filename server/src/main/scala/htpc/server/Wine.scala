package htpc.server

import cats.effect.{IO, Ref, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all.*
import htpc.protocol.*
import io.circe.Json
import io.circe.parser.parse
import java.io.{DataInputStream, DataOutputStream, EOFException}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Framed, bounded messages to a long-lived Windows DLL host. */
private final class DllBridge(val process: Process):
  private val input = new DataInputStream(process.getInputStream)
  private val output = new DataOutputStream(process.getOutputStream)

  // Kill the writer before cancelling a blocked pipe reader. Java pipe reads are
  // not reliably interruptible, so a plain IO.blocking(...).timeout can hang.
  private def bounded[A](work: IO[A], timeout: FiniteDuration): IO[A] = IO.uncancelable { _ =>
    IO.racePair(work, IO.sleep(timeout)).flatMap {
      case Left((outcome, timer)) => timer.cancel *> outcome.embedNever
      case Right((reader, _)) => Resources.stopProcess(process) *> reader.cancel *>
        IO.raiseError(Fault(ErrorCode.Timeout, "DLL host timed out and was terminated; create a new instance before retrying"))
    }
  }

  def ready: IO[Unit] = bounded(IO.blocking {
    val bytes = new Array[Byte](8)
    input.readFully(bytes)
    if new String(bytes, UTF_8) != "HTPCDLL1" then throw Fault(ErrorCode.Protocol, "DLL host returned an invalid handshake")
  }, 60.seconds)

  def execute(call: DllInvocation, timeout: FiniteDuration): IO[Json] = bounded(IO.blocking {
    val dll = Paths.get(call.dll).toAbsolutePath.normalize()
    if !Files.isRegularFile(dll) then throw Fault(ErrorCode.NotFound, s"DLL file does not exist: $dll")
    val fields = Vector("Z:" + dll.toString.replace('/', '\\'), call.function, call.freeFunction.getOrElse(""), call.arguments.noSpaces)
    fields.foreach { field =>
      val bytes = field.getBytes(UTF_8)
      if bytes.length > 1024 * 1024 then throw Fault(ErrorCode.InvalidArgument, "DLL invocation field exceeds 1 MiB")
      output.writeInt(Integer.reverseBytes(bytes.length))
      output.write(bytes)
    }
    output.flush()
    val status = Integer.reverseBytes(input.readInt())
    val length = Integer.reverseBytes(input.readInt())
    if length < 0 || length > 4 * 1024 * 1024 then throw Fault(ErrorCode.Protocol, "DLL host returned an invalid frame size")
    val bytes = new Array[Byte](length)
    input.readFully(bytes)
    val text = new String(bytes, UTF_8)
    if status != 0 then throw Fault(ErrorCode.Execution, text)
    parse(text).fold(e => throw Fault(ErrorCode.Serialization, s"DLL returned invalid JSON: ${e.message}"), identity)
  }, timeout).handleErrorWith {
    case _: EOFException => IO.raiseError(Fault(ErrorCode.Disconnected, "DLL host exited or crashed during execution"))
    case t => IO.raiseError(t)
  }

private final case class WineEntry(info: WineInstance, bridge: DllBridge, lock: Semaphore[IO], release: IO[Unit])

final class Wine private(config: Config, events: EventHub, entries: Ref[IO, Map[WineId, WineEntry]], lifecycle: Semaphore[IO]):
  def capability: Capability =
    if config.wine.isEmpty || config.wineServer.isEmpty then Capability(false, "Configure HTPC_WINE and HTPC_WINESERVER")
    else if !Files.isRegularFile(config.dllHost) then Capability(false, s"Build DLL host first: ${config.dllHost}")
    else Capability(true, s"${config.wine.get} · json-utf8-v1 · win64")

  def handle[A](op: WineOp[A]): IO[Either[WineError, A]] = run(op).attempt.map(_.left.map { t =>
    val f = Fault.from(t); WineError(f.code, f.getMessage)
  })

  private def entry(id: WineId): IO[WineEntry] = entries.get.flatMap(_.get(id).liftTo[IO](Fault(ErrorCode.NotFound, "Wine instance does not exist")))
  private def state(e: WineEntry): WineInstance = e.info.copy(state = if e.bridge.process.isAlive then InstanceState.Running else InstanceState.Failed)

  private def stopPrefix(profile: Path): IO[Unit] = config.wineServer.fold(IO.unit) { executable =>
    Resource.make(IO.blocking {
      val builder = new ProcessBuilder(executable, "-k")
      builder.environment().put("WINEPREFIX", profile.toString)
      builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    })(Resources.stopProcess).use(p => IO.blocking(p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)).void)
  }

  private def create(options: WineOptions): IO[WineInstance] = lifecycle.permit.use { _ => IO.uncancelable { poll => for
    _ <- IO.raiseUnless(capability.available)(Fault(ErrorCode.Unavailable, capability.detail))
    _ <- Fault.require(options.name.nonEmpty && options.name.length <= 120, "Name must contain 1–120 characters")
    current <- entries.get
    _ <- IO.raiseWhen(options.profile.exists(p => current.values.exists(_.info.profile.contains(p))))(Fault(ErrorCode.Conflict, "Prefix is already owned by an instance"))
    id = Resources.id[WineKind]
    allocated <- poll((for
      prefix <- Resource.make(Resources.profile(config.data.resolve("wine"), options.profile, id.value))(p =>
        stopPrefix(p) *> (if options.profile.isEmpty then Resources.deleteTree(p) else IO.unit))
      process <- Resource.make(IO.blocking {
        val builder = new ProcessBuilder(config.wine.get, config.dllHost.toString)
        builder.environment().put("WINEPREFIX", prefix.toString)
        builder.environment().put("WINEARCH", "win64")
        builder.environment().put("WINEDEBUG", "-all")
        builder.environment().put("WINEDLLOVERRIDES", "mscoree,mshtml=")
        builder.redirectError(prefix.resolve("host.log").toFile).start()
      })(Resources.stopProcess)
      bridge = new DllBridge(process)
      _ <- Resource.eval(bridge.ready.handleErrorWith { error =>
        IO.blocking {
          val log = prefix.resolve("host.log")
          if Files.exists(log) then Files.readString(log).takeRight(3000) else "No host log"
        }.flatMap(log => IO.raiseError(Fault(ErrorCode.Execution, s"DLL host startup failed: ${Option(error.getMessage).getOrElse(error.getClass.getSimpleName)}\n$log")))
      })
      lock <- Resource.eval(Semaphore[IO](1))
    yield (bridge, lock)).allocated)
    ((bridge, lock), release) = allocated
    info = WineInstance(id, options.name, options.profile, InstanceState.Running)
    _ <- entries.update(_ + (id -> WineEntry(info, bridge, lock, release)))
    _ <- events.publish(Topic.Wine, WineEvent(WineEventKind.InstanceCreated, id))
  yield info }}

  private def run[A](op: WineOp[A]): IO[A] = op match
    case WineOp.ListInstance => entries.get.map(_.values.toVector.map(state).sortBy(_.id.value))
    case WineOp.NewInstance(options) => create(options)
    case WineOp.GetInstance(id) => entry(id).map(state)
    case WineOp.DeleteInstance(id) => lifecycle.permit.use(_ => entry(id).flatMap(e => e.lock.permit.use { _ =>
      IO.uncancelable(_ => entries.update(_ - id) *> e.release *>
        events.publish(Topic.Wine, WineEvent(WineEventKind.InstanceDeleted, id)).as(Done()))
    }))
    case WineOp.Execute(id, invocation, options) => (for
      duration <- Fault.timeout(options.timeoutMs)
      _ <- Fault.require(invocation.function.nonEmpty && invocation.function.length <= 256 && !invocation.function.contains('\u0000'), "Invalid DLL export name")
      e <- entry(id)
      result <- e.lock.permit.use { _ =>
        entry(id) *> IO.raiseUnless(e.bridge.process.isAlive)(Fault(ErrorCode.Disconnected, "DLL host is no longer running")) *>
          e.bridge.execute(invocation, duration).attempt.flatMap {
            case Right(value) => events.publish(Topic.Wine, WineEvent(WineEventKind.ExecutionCompleted, id)).as(value)
            case Left(error) =>
              (if !e.bridge.process.isAlive then events.publish(Topic.Wine, WineEvent(WineEventKind.InstanceFailed, id)) else IO.unit) *>
                IO.raiseError(error)
          }
      }
    yield result)

  def close: IO[Unit] = entries.getAndSet(Map.empty).flatMap(es => Resources.cleanup(es.values.map(_.release)))

object Wine:
  def resource(config: Config, events: EventHub): Resource[IO, Wine] = Resource.make(for
    entries <- Ref.of[IO, Map[WineId, WineEntry]](Map.empty)
    lock <- Semaphore[IO](1)
  yield new Wine(config, events, entries, lock))(_.close)
