package htpc.server

import cats.effect.{Deferred, IO}
import cats.effect.std.{Dispatcher, Queue}
import cats.syntax.all.*
import fs2.Stream
import htpc.protocol.*
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import org.http4s.Response
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import scala.concurrent.duration.*

/** One upstream connection per frontend socket: command IDs and target sessions stay isolated. */
object BrowserSocket:
  def open(url: String, builder: WebSocketBuilder2[IO], dispatcher: Dispatcher[IO]): IO[Response[IO]] = for
    outgoing <- Queue.bounded[IO, WebSocketFrame](256)
    closed <- Deferred[IO, Unit]
    upstream <- Cdp.resource(url, event => dispatcher.unsafeRunAndForget {
      if event.hcursor.get[String]("method").contains("htpc.disconnected") then closed.complete(()).void
      else outgoing.tryOffer(WebSocketFrame.Text(event.noSpaces)).flatMap(ok => if ok then IO.unit else closed.complete(()).void)
    }).allocated
    (cdp, release) = upstream
    send = Stream.fromQueueUnterminated(outgoing).interruptWhen(closed.get.attempt) ++ Stream.emit(WebSocketFrame.Close())
    receive = (input: Stream[IO, WebSocketFrame]) => input.parEvalMapUnordered(32) {
      case WebSocketFrame.Text(text, _) =>
        val parsed = parse(text).toOption.filter(_ => text.length <= 4 * 1024 * 1024)
        parsed match
          case Some(json) if json.hcursor.get[Long]("id").isRight && json.hcursor.get[String]("method").isRight =>
            val c = json.hcursor
            cdp.call(c.get[String]("method").toOption.get, c.downField("params").focus.getOrElse(Json.obj()),
              c.get[String]("sessionId").toOption, 120.seconds).attempt.flatMap { result =>
              val response = Json.obj("id" -> c.get[Long]("id").toOption.get.asJson).deepMerge(result match
                case Right(value) => Json.obj("result" -> value)
                case Left(error) => Json.obj("error" -> Json.obj("code" -> (-32000).asJson, "message" -> Fault.from(error).getMessage.asJson)))
              outgoing.offer(WebSocketFrame.Text(response.deepMerge(c.get[String]("sessionId").toOption.fold(Json.obj())(s => Json.obj("sessionId" -> s.asJson))).noSpaces))
            }
          case _ => closed.complete(()).void
      case _: WebSocketFrame.Close => closed.complete(()).void
      case _ => IO.unit
    }.drain.interruptWhen(closed.get.attempt)
    response <- builder.withOnClose(closed.complete(()).void *> release).build(send, receive).onError(_ => release)
  yield response
