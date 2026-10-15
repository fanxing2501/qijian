package htpc.server

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import htpc.protocol.*
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import java.net.{StandardProtocolFamily, UnixDomainSocketAddress}
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import scala.concurrent.duration.*

object SwayWire:
  val MaxPayload = 16 * 1024 * 1024
  def write(channel: SocketChannel, kind: Int, value: String): Unit =
    val payload = value.getBytes(UTF_8)
    val buffer = ByteBuffer.allocate(14 + payload.length).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("i3-ipc".getBytes(UTF_8)).putInt(payload.length).putInt(kind).put(payload).flip()
    while buffer.hasRemaining do channel.write(buffer)

  def read(channel: SocketChannel): (Int, Json) =
    def fully(buffer: ByteBuffer): Unit =
      while buffer.hasRemaining do
        if channel.read(buffer) < 0 then throw Fault(ErrorCode.Disconnected, "Sway IPC closed")
    val header = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN)
    fully(header); header.flip()
    val magic = new Array[Byte](6)
    header.get(magic)
    if new String(magic, UTF_8) != "i3-ipc" then throw Fault(ErrorCode.Protocol, "Invalid Sway IPC header")
    val size = header.getInt()
    val kind = header.getInt()
    if size < 0 || size > MaxPayload then throw Fault(ErrorCode.Protocol, "Invalid Sway IPC payload size")
    val payload = ByteBuffer.allocate(size)
    fully(payload)
    val json = parse(new String(payload.array(), UTF_8)).fold(e => throw Fault(ErrorCode.Protocol, e.message), identity)
    (kind, json)

final class Sway(config: Config, events: EventHub):
  def capability: Capability = config.swaySocket match
    case Some(p) if Files.exists(p) => Capability(true, p.toString)
    case _ => Capability(false, "Set SWAYSOCK to the compositor's IPC socket")

  private def socketPath: IO[Path] = config.swaySocket.filter(Files.exists(_))
    .liftTo[IO](Fault(ErrorCode.Unavailable, capability.detail))

  private def sessionId(path: Path): IO[String] = IO.blocking {
    val attributes = Files.readAttributes(path, classOf[BasicFileAttributes])
    UUID.nameUUIDFromBytes(s"$path:${attributes.fileKey()}:${attributes.creationTime()}".getBytes(UTF_8)).toString
  }

  private def connection: Resource[IO, (SocketChannel, String)] = for
    path <- Resource.eval(socketPath)
    socket <- Resource.make(IO.interruptibleMany {
      val c = SocketChannel.open(StandardProtocolFamily.UNIX)
      try { c.connect(UnixDomainSocketAddress.of(path)); c }
      catch { case t: Throwable => c.close(); throw t }
    })(c => IO.blocking(c.close()))
    session <- Resource.eval(sessionId(path))
  yield (socket, session)

  private def query(channel: SocketChannel, kind: Int, payload: String = ""): IO[Json] = IO.interruptibleMany {
    SwayWire.write(channel, kind, payload)
    val (responseKind, response) = SwayWire.read(channel)
    if responseKind != kind then throw Fault(ErrorCode.Protocol, "Unexpected Sway response type")
    response
  }

  private def snapshot: IO[DisplaySnapshot] = connection.use { (channel, session) => for
    tree <- query(channel, 4)
    workspaces <- query(channel, 1).flatMap(j => IO.fromEither(j.as[Vector[WorkspaceInfo]]))
    outputs <- query(channel, 3).flatMap(j => IO.fromEither(j.as[Vector[Json]]))
    result <- IO.fromEither(outputs.traverse { j =>
      val c = j.hcursor
      for
        name <- c.get[String]("name")
        active <- c.get[Boolean]("active")
        current <- c.get[Option[Json]]("current_mode").flatMap(_.traverse(Sway.parseMode))
        modes <- c.get[Vector[Json]]("modes").flatMap(_.traverse(Sway.parseMode))
      yield OutputInfo(name, active, c.get[Boolean]("power").orElse(c.get[Boolean]("dpms")).getOrElse(active), current, modes)
    })
  yield DisplaySnapshot(session, Sway.windows(tree, session), workspaces, result) }

  private def command(value: String, window: Option[WindowRef] = None): IO[Done] = connection.use { (channel, session) => for
    _ <- window.traverse_ { w =>
      IO.raiseUnless(w.session == session && w.container > 0)(Fault(ErrorCode.Conflict, "Window reference belongs to an old compositor session")) *>
        query(channel, 4).flatMap(tree => IO.raiseUnless(Sway.windows(tree, session).exists(_.ref == w))(Fault(ErrorCode.NotFound, "Window no longer exists")))
    }
    result <- query(channel, 0, value)
    entries <- IO.fromEither(result.as[Vector[Json]])
    _ <- entries.traverse_ { r =>
      IO.raiseUnless(r.hcursor.get[Boolean]("success").contains(true))(Fault(ErrorCode.Execution,
        r.hcursor.get[String]("error").getOrElse("Sway rejected the command")))
    }
  yield Done() }

  private def quoted(value: String): IO[String] = Fault.require(value.nonEmpty && value.length <= 200 && !value.exists(_.isControl), "Invalid output or workspace name").as(Json.fromString(value).noSpaces)
  private def selector(w: WindowRef): String = s"[con_id=${w.container}]"

  def handle[A](op: SwayOp[A]): IO[Either[SwayError, A]] = run(op).timeout(10.seconds).attempt.map(_.left.map { t =>
    val f = Fault.from(t); SwayError(f.code, f.getMessage)
  })

  private def run[A](op: SwayOp[A]): IO[A] = op match
    case SwayOp.Snapshot => snapshot
    case SwayOp.Focus(w) => command(s"${selector(w)} focus", Some(w))
    case SwayOp.SetFullscreen(w, enabled) => command(s"${selector(w)} fullscreen ${if enabled then "enable" else "disable"}", Some(w))
    case SwayOp.Move(w, workspace) => quoted(workspace).flatMap(s => command(s"${selector(w)} move container to workspace $s", Some(w)))
    case SwayOp.Close(w) => command(s"${selector(w)} kill", Some(w))
    case SwayOp.ActivateWorkspace(workspace) => quoted(workspace).flatMap(s => command(s"workspace $s"))
    case SwayOp.SetOutputPower(output, enabled) => (for
      state <- snapshot
      _ <- IO.raiseUnless(state.outputs.exists(_.name == output))(Fault(ErrorCode.NotFound, "Output does not exist"))
      name <- quoted(output)
      done <- command(s"output $name power ${if enabled then "on" else "off"}")
    yield done)
    case SwayOp.SetOutputMode(output, mode) => (for
      state <- snapshot
      _ <- IO.raiseUnless(state.outputs.exists(o => o.name == output && o.modes.contains(mode)))(Fault(ErrorCode.InvalidArgument, "Output does not advertise the requested mode"))
      name <- quoted(output)
      refresh = BigDecimal(mode.refreshMilliHz) / 1000
      done <- command(s"output $name mode ${mode.width}x${mode.height}@${refresh}Hz")
    yield done)

  private def watchOnce: IO[Unit] = connection.use { (channel, _) =>
    query(channel, 2, "[\"window\",\"workspace\",\"output\",\"shutdown\"]") *>
      (IO.interruptibleMany(SwayWire.read(channel)).flatMap { (_, event) =>
        events.publish(Topic.Sway, DisplayEvent(event.hcursor.get[String]("change").getOrElse("output")))
      }).foreverM
  }

  def watch: IO[Unit] =
    if config.swaySocket.isEmpty then IO.never
    else (watchOnce.attempt *> IO.sleep(3.seconds)).foreverM

object Sway:
  private[server] def parseMode(j: Json): io.circe.Decoder.Result[OutputMode] = for
    w <- j.hcursor.get[Int]("width")
    h <- j.hcursor.get[Int]("height")
    r <- j.hcursor.get[Int]("refresh")
  yield OutputMode(w, h, r)

  private[server] def windows(tree: Json, session: String): Vector[WindowInfo] =
    def walk(node: Json, workspace: String): Vector[WindowInfo] =
      val c = node.hcursor
      val ws = if c.get[String]("type").contains("workspace") then c.get[String]("name").getOrElse("") else workspace
      val children = c.get[Vector[Json]]("nodes").getOrElse(Vector.empty) ++ c.get[Vector[Json]]("floating_nodes").getOrElse(Vector.empty)
      val isWindow = c.get[Option[String]]("app_id").toOption.flatten.nonEmpty || c.get[Option[Long]]("window").toOption.flatten.nonEmpty
      val own = if !isWindow then Vector.empty else c.get[Long]("id").toOption.toVector.map { id =>
        WindowInfo(WindowRef(session, id), c.get[String]("name").getOrElse(""),
          c.get[Option[String]]("app_id").toOption.flatten.orElse(c.downField("window_properties").get[String]("class").toOption),
          ws, c.get[Boolean]("focused").getOrElse(false), c.get[Int]("fullscreen_mode").getOrElse(0) != 0, c.get[Long]("pid").toOption)
      }
      own ++ children.flatMap(walk(_, ws))
    walk(tree, "")
