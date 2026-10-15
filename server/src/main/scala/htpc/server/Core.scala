package htpc.server

import cats.effect.{IO, Ref, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.Topic as FsTopic
import htpc.protocol.*
import io.circe.{Encoder, Json}
import io.circe.syntax.*
import java.nio.file.{Files, Path, Paths}
import java.util.UUID
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

final case class Fault(code: ErrorCode, override val getMessage: String) extends RuntimeException(getMessage)
object Fault:
  def from(t: Throwable): Fault = t match
    case f: Fault => f
    case _: java.util.concurrent.TimeoutException => Fault(ErrorCode.Timeout, "Operation timed out")
    case other => Fault(ErrorCode.Internal, Option(other.getMessage).getOrElse(other.getClass.getSimpleName))
  def require(condition: Boolean, message: String): IO[Unit] =
    IO.raiseUnless(condition)(Fault(ErrorCode.InvalidArgument, message))
  def timeout(ms: Int): IO[FiniteDuration] =
    require(ms >= 100 && ms <= 120000, "timeoutMs must be between 100 and 120000").as(ms.millis)

final case class Config(host: String, port: Int, data: Path, web: Path, chromium: Option[String], wine: Option[String], wineServer: Option[String], dllHost: Path, swaySocket: Option[Path], hostServices: Option[Path] = None)
object Config:
  def executable(name: String): Option[String] =
    if name.contains("/") then Option.when(Files.isExecutable(Paths.get(name)))(name)
    else sys.env.getOrElse("PATH", "").split(":").iterator.map(p => Paths.get(p, name)).find(Files.isExecutable(_)).map(_.toString)

  def load: Config =
    val home = Paths.get(System.getProperty("user.home"))
    def program(key: String, names: String*): Option[String] =
      sys.env.get(key).flatMap(executable).orElse(names.iterator.flatMap(executable).take(1).toList.headOption)
    Config(
      sys.env.getOrElse("HTPC_HOST", "0.0.0.0"),
      sys.env.getOrElse("HTPC_PORT", "8787").toInt,
      Paths.get(sys.env.getOrElse("HTPC_DATA", home.resolve(".local/share/htpc-next").toString)).toAbsolutePath,
      Paths.get(sys.env.getOrElse("HTPC_WEB", "dist/web")).toAbsolutePath,
      program("HTPC_CHROMIUM", "chromium", "google-chrome", "chromium-browser"),
      program("HTPC_WINE", "wine", "wine64"),
      program("HTPC_WINESERVER", "wineserver"),
      Paths.get(sys.env.getOrElse("HTPC_DLL_HOST", "dist/native/dll-host.exe")).toAbsolutePath,
      sys.env.get("SWAYSOCK").filter(_.nonEmpty).map(Paths.get(_)),
      Some(Paths.get(sys.env.getOrElse("HTPC_HOST_SERVICES", home.resolve(".config/htpc-next/host-services.json").toString)))
    )

final class EventHub private(val epoch: String, counter: Ref[IO, Long], topic: FsTopic[IO, EventEnvelope], lock: Semaphore[IO]):
  def publish[E](t: htpc.protocol.Topic[E], value: E): IO[Unit] = lock.permit.use { _ =>
    counter.updateAndGet(_ + 1).flatMap(n => topic.publish1(EventEnvelope(epoch, n, t.module, t.codec(value))).void)
  }
  // Every connection starts with a resync signal. Subscribe before producing it so
  // changes racing with the client's snapshot cannot be lost.
  def subscribe: Resource[IO, Stream[IO, EventEnvelope]] = topic.subscribeAwait(256)

object EventHub:
  def create: IO[EventHub] = for
    counter <- Ref.of[IO, Long](0)
    topic <- FsTopic[IO, EventEnvelope]
    lock <- Semaphore[IO](1)
  yield new EventHub(UUID.randomUUID().toString, counter, topic, lock)

object Resources:
  def id[K]: Id[K] = Id(UUID.randomUUID().toString)

  def profile(root: Path, name: Option[String], instance: String): IO[Path] = for
    _ <- Fault.require(name.forall(_.matches("[A-Za-z0-9_-]{1,64}")), "Profile must contain 1–64 letters, digits, '_' or '-'")
    path = name.fold(root.resolve("temporary").resolve(instance))(n => root.resolve("profiles").resolve(n))
    _ <- IO.blocking(Files.createDirectories(path))
  yield path

  def deleteTree(path: Path): IO[Unit] = IO.blocking {
    if Files.exists(path) then
      val paths = Files.walk(path)
      try paths.iterator().asScala.toVector.reverse.foreach(Files.deleteIfExists(_))
      finally paths.close()
  }

  def stopProcess(process: Process): IO[Unit] = IO.blocking {
    val descendants = process.descendants().toList.asScala.toVector.reverse
    descendants.foreach(_.destroy())
    process.destroy()
    if !process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) then process.destroyForcibly()
    descendants.filter(_.isAlive).foreach(_.destroyForcibly())
    process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
    ()
  }

  def cleanup(entries: Iterable[IO[Unit]]): IO[Unit] = entries.toList.traverse_(_.attempt.void)
