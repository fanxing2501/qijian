package htpc.server

import cats.effect.{IO, IOApp, Ref, Resource}
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import com.comcast.ip4s.{Host, Port}
import fs2.Stream
import htpc.protocol.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.ci.CIString
import java.nio.file.Files
import scala.concurrent.duration.*

final class ServiceInterpreter(browser: Chromium, dll: Wine, display: Sway, events: EventHub, serverStorage: ServerStorage, local: HostServices, gameDisplay: GameDisplayService) extends Interpreter[IO]:
  def chromium[A](op: ChromiumOp[A]) = browser.handle(op)
  def wine[A](op: WineOp[A]) = dll.handle(op)
  def sway[A](op: SwayOp[A]) = display.handle(op)
  def storage[A](op: StorageOp[A]) = serverStorage.handle(op, browser)
  def host[A](op: HostOp[A]) = local.handle(op)
  def system[A](op: SystemOp[A]): IO[Either[SystemError, A]] = op match
    case SystemOp.GetCapabilities => IO.pure(Right(Capabilities(browser.capability, dll.capability, display.capability, events.epoch)))
    case SystemOp.QrCode(text) => QrCode.render(text)
    case SystemOp.GameDisplayStatus => IO.pure(Right(gameDisplay.support))
    case SystemOp.PrepareGameDisplay(application, settings) => gameDisplay.prepare(application, settings)

final class Routes(config: Config, interpreter: Interpreter[IO], events: EventHub, browser: Chromium, dispatcher: Dispatcher[IO], local: HostServices):
  private def execute(id: String, packed: PackedRequest): IO[RpcResponse] =
    packed.request.accept(interpreter).map {
      case Right(value) => RpcResponse(id, "result", packed.request.resultCodec(value))
      case Left(error) => RpcResponse(id, "error", packed.request.errorCodec(error))
    }

  private def rpc(request: org.http4s.Request[IO], module: Module): IO[Response[IO]] =
    request.as[Json].attempt.flatMap {
      case Left(_) => BadRequest(RpcResponse("", "protocol-error", ProtocolError("Expected a JSON request body").asJson).asJson)
      case Right(json) => json.as[RpcRequest] match
        case Left(error) => BadRequest(RpcResponse("", "protocol-error", ProtocolError(error.getMessage).asJson).asJson)
        case Right(wire) if wire.id.isEmpty || wire.id.length > 160 =>
          BadRequest(RpcResponse(wire.id.take(160), "protocol-error", ProtocolError("Request id must contain 1–160 characters").asJson).asJson)
        case Right(wire) => htpc.protocol.Protocol.decode(module, wire) match
          case Left(error) => BadRequest(RpcResponse(wire.id, "protocol-error", error.asJson).asJson)
          case Right(packed) => execute(wire.id, packed).flatMap(r => Ok(r.asJson))
    }

  private val noStore = Header.Raw(CIString("Cache-Control"), "no-store")
  private val modules = Map("system" -> Module.System, "chromium" -> Module.Chromium, "wine" -> Module.Wine, "sway" -> Module.Sway, "storage" -> Module.Storage, "host" -> Module.Host)

  def routes(ws: org.http4s.server.websocket.WebSocketBuilder2[IO]): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ GET -> Root / "api" / "v1" / "host" / service / "targets" / target / "cdp" =>
      local.socketEndpoint(service, target).attempt.flatMap {
        case Right(url) =>
          if request.headers.get(CIString("Upgrade")).exists(_.head.value.equalsIgnoreCase("websocket")) then BrowserSocket.open(url, ws, dispatcher)
          else BadRequest("Expected a WebSocket upgrade")
        case Left(_) => NotFound()
      }
    case request @ GET -> Root / "api" / "v1" / "chromium" / id / "cdp" =>
      browser.socketEndpoint(Id(id)).attempt.flatMap {
        case Right(url) =>
          if request.headers.get(CIString("Upgrade")).exists(_.head.value.equalsIgnoreCase("websocket")) then BrowserSocket.open(url, ws, dispatcher)
          else BadRequest("Expected a WebSocket upgrade")
        case Left(_) => NotFound()
      }
    case GET -> Root / "api" / "v1" / "health" => Ok(Json.obj("status" -> "ok".asJson, "epoch" -> events.epoch.asJson))
    case request @ POST -> Root / "api" / "v1" / module if modules.contains(module) =>
      rpc(request, modules(module)).map(_.putHeaders(noStore))
    case GET -> Root / "api" / "v1" / "events" =>
      val stream = Stream.resource(events.subscribe).flatMap { updates =>
        val sync = Stream.emit(s"event: sync\ndata: ${Json.obj("epoch" -> events.epoch.asJson).noSpaces}\n\n")
        val messages = updates.map(e => s"id: ${e.epoch}:${e.sequence}\nevent: change\ndata: ${e.asJson.noSpaces}\n\n")
        sync ++ messages.merge(Stream.awakeEvery[IO](15.seconds).map(_ => ": heartbeat\n\n"))
      }
      Ok(stream.through(fs2.text.utf8.encode)).map(_.putHeaders(noStore,
        Header.Raw(CIString("Content-Type"), "text/event-stream"), Header.Raw(CIString("X-Accel-Buffering"), "no")))
    case GET -> Root => StaticFile.fromPath[IO](fs2.io.file.Path.fromNioPath(config.web.resolve("index.html")), None).getOrElseF(NotFound()).map(_.putHeaders(noStore))
    case GET -> Root / "console" => StaticFile.fromPath[IO](fs2.io.file.Path.fromNioPath(config.web.resolve("console.html")), None).getOrElseF(NotFound()).map(_.putHeaders(noStore))
    case request @ GET -> Root / asset if asset.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]*\\.(js|map|css|svg|png|jpg|jpeg|webp|avif)") =>
      StaticFile.fromPath(fs2.io.file.Path.fromNioPath(config.web.resolve(asset)), Some(request)).getOrElseF(NotFound())
  }

object Main extends IOApp.Simple:
  def application(config: Config): Resource[IO, org.http4s.server.Server] = for
    _ <- Resource.eval(IO.blocking(Files.createDirectories(config.data)))
    events <- Resource.eval(EventHub.create)
    dispatcher <- Dispatcher.parallel[IO]
    browser <- Chromium.resource(config, events, dispatcher)
    wine <- Wine.resource(config, events)
    sway = new Sway(config, events)
    storage <- Resource.eval(ServerStorage.create(config.data.resolve("storage")))
    _ <- Resource.make(sway.watch.start)(_.cancel)
    local = new HostServices(config.hostServices)
    interpreter = new ServiceInterpreter(browser, wine, sway, events, storage, local, new GameDisplayService(config, sway))
    routes = new Routes(config, interpreter, events, browser, dispatcher, local)
    server <- EmberServerBuilder.default[IO]
      .withHost(Host.fromString(config.host).getOrElse(throw IllegalArgumentException("Invalid HTPC_HOST")))
      .withPort(Port.fromInt(config.port).getOrElse(throw IllegalArgumentException("Invalid HTPC_PORT")))
      .withIdleTimeout(180.seconds)
      .withHttpWebSocketApp(ws => org.http4s.server.middleware.EntityLimiter.httpApp[IO](routes.routes(ws).orNotFound, 4L * 1024 * 1024))
      .build
  yield server

  def run: IO[Unit] = IO(Config.load).flatMap { config =>
    application(config).use(_ => IO.println(s"HTPC Next: http://${config.host}:${config.port} · data ${config.data}") *> IO.never)
  }
