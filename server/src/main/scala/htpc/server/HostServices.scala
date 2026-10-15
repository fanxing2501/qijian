package htpc.server

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import htpc.protocol.*
import io.circe.{Codec, Json}
import io.circe.parser.parse
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

/** Only host-configured user units and loopback CDP endpoints. No application logic. */
final class HostServices(config: Option[Path]):
  import HostServices.*
  private val http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build()

  private def definition(name: String): IO[Definition] = for
    _ <- Fault.require(validName(name), "Invalid service name")
    path <- config.filter(Files.isRegularFile(_)).liftTo[IO](Fault(ErrorCode.Unavailable, "Local services are not configured"))
    text <- IO.blocking(Files.readString(path))
    entries <- IO.fromEither(parse(text).flatMap(_.as[Map[String, Definition]]))
    entry <- entries.get(name).liftTo[IO](Fault(ErrorCode.NotFound, "Local service is not configured"))
    _ <- Fault.require(entry.unit.matches("groot-[A-Za-z0-9_.@-]+\\.service"), "Invalid configured user unit")
  yield entry

  private def command(args: String*): IO[String] = Resource.make(IO.blocking {
    new ProcessBuilder((Vector("systemctl", "--user", "--no-pager") ++ args)*).redirectError(ProcessBuilder.Redirect.DISCARD).start()
  })(Resources.stopProcess).use { process =>
    IO.fromCompletableFuture(IO(process.onExit())).timeout(20.seconds) *>
      IO.blocking(new String(process.getInputStream.readNBytes(65536), java.nio.charset.StandardCharsets.UTF_8)).flatMap { result =>
        IO.raiseUnless(process.exitValue() == 0)(Fault(ErrorCode.Execution, "User service command failed; inspect the service journal")).as(result)
      }
  }

  private def status(name: String, d: Definition): IO[HostService] = command("show", d.unit,
    "--property=LoadState", "--property=ActiveState", "--property=SubState", "--property=MainPID").map { text =>
    val properties = text.linesIterator.flatMap(line => line.split("=", 2) match
      case Array(k, v) => Some(k -> v)
      case _ => None).toMap
    HostService(name, properties.get("LoadState").contains("loaded"), properties.getOrElse("ActiveState", "unknown"),
      properties.getOrElse("SubState", "unknown"), properties.get("MainPID").flatMap(_.toLongOption).getOrElse(0L))
  }

  private def targets(name: String): IO[(URI, Vector[Json])] = for
    d <- definition(name)
    base <- IO.fromEither(d.cdp.toRight(Fault(ErrorCode.Unavailable, "This service has no CDP endpoint"))).flatMap(s => IO(URI.create(s)))
    _ <- Fault.require(validEndpoint(base), "CDP endpoint must be an explicit loopback HTTP address")
    request = HttpRequest.newBuilder(base.resolve("/json/list")).timeout(java.time.Duration.ofSeconds(5)).GET().build()
    response <- IO.fromCompletableFuture(IO(http.sendAsync(request, HttpResponse.BodyHandlers.ofString())))
    _ <- IO.raiseUnless(response.statusCode() == 200)(Fault(ErrorCode.Unavailable, "Local CDP endpoint is not ready"))
    _ <- Fault.require(response.body().length <= 4 * 1024 * 1024, "CDP discovery response is too large")
    json <- IO.fromEither(parse(response.body()).flatMap(_.as[Vector[Json]]))
  yield (base, json)

  def socketEndpoint(name: String, target: String): IO[String] = for
    _ <- Fault.require(validName(target), "Invalid target id")
    found <- targets(name)
    (base, entries) = found
    entry <- entries.find(_.hcursor.get[String]("id").contains(target)).liftTo[IO](Fault(ErrorCode.NotFound, "CDP target no longer exists"))
    url <- IO.fromEither(entry.hcursor.get[String]("webSocketDebuggerUrl"))
    _ <- Fault.require(validSocket(base, URI.create(url)), "CDP target points outside its configured endpoint")
  yield url

  def handle[A](op: HostOp[A]): IO[Either[HostError, A]] = run(op).attempt.map(_.left.map { t =>
    val f = Fault.from(t); HostError(f.code, f.getMessage)
  })

  private def run[A](op: HostOp[A]): IO[A] = op match
    case HostOp.Status(name) => definition(name).flatMap(status(name, _))
    case HostOp.Start(name) => definition(name).flatMap(d => command("start", d.unit) *> status(name, d))
    case HostOp.Stop(name) => definition(name).flatMap(d => command("stop", d.unit) *> status(name, d))
    case HostOp.Targets(name) => targets(name).flatMap { (_, entries) => IO.fromEither(entries.traverse { j =>
      val c = j.hcursor
      for id <- c.get[String]("id"); title <- c.get[String]("title"); url <- c.get[String]("url"); kind <- c.get[String]("type")
      yield HostTarget(id, title, url, kind)
    }) }
    case HostOp.Socket(name, target) => socketEndpoint(name, target).as(ChromiumSocket(s"/api/v1/host/$name/targets/$target/cdp"))

object HostServices:
  final case class Definition(unit: String, cdp: Option[String]) derives Codec.AsObject
  private[server] def validName(s: String): Boolean = s.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}")
  private[server] def validEndpoint(u: URI): Boolean =
    u.getScheme == "http" && Set("127.0.0.1", "[::1]").contains(u.getHost) && u.getPort > 0 && u.getUserInfo == null && u.getRawQuery == null && u.getFragment == null
  private[server] def validSocket(base: URI, socket: URI): Boolean =
    validEndpoint(base) && socket.getScheme == "ws" && socket.getHost == base.getHost && socket.getPort == base.getPort && socket.getUserInfo == null && socket.getFragment == null
