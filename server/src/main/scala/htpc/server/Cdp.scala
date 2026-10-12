package htpc.server

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import htpc.protocol.ErrorCode
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import java.net.URI
import java.net.http.{HttpClient, WebSocket}
import java.util.concurrent.{CompletableFuture, CompletionStage, ConcurrentHashMap}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** CDP multiplexing over a single browser socket, with flattened target sessions. */
final class Cdp private(onEvent: Json => Unit) extends WebSocket.Listener:
  private val ids = new AtomicLong(0)
  private val closed = new AtomicBoolean(false)
  private val pending = new ConcurrentHashMap[Long, CompletableFuture[Json]]()
  private val text = new StringBuilder
  @volatile private var socket: WebSocket = null

  def connect(url: String): IO[Unit] = IO.fromCompletableFuture(IO {
    HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build()
      .newWebSocketBuilder().connectTimeout(java.time.Duration.ofSeconds(10))
      .buildAsync(URI.create(url), this)
  }).flatMap(ws => IO { socket = ws })

  override def onOpen(ws: WebSocket): Unit = ws.request(1)
  override def onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage[?] =
    text.append(data)
    if last then
      val message = text.toString
      text.clear()
      parse(message).foreach { json =>
        json.hcursor.get[Long]("id").toOption match
          case Some(id) =>
            Option(pending.remove(id)).foreach { promise =>
              json.hcursor.downField("error").focus match
                case Some(error) => promise.completeExceptionally(Fault(ErrorCode.Execution,
                  error.hcursor.get[String]("message").getOrElse("CDP command failed")))
                case None => promise.complete(json.hcursor.downField("result").focus.getOrElse(Json.obj()))
            }
          case None => onEvent(json)
      }
    ws.request(1)
    CompletableFuture.completedFuture(null)

  private def fail(error: Throwable): Unit =
    if closed.compareAndSet(false, true) then
      pending.values().asScala.foreach(_.completeExceptionally(error))
      pending.clear()
      onEvent(Json.obj("method" -> "htpc.disconnected".asJson))

  override def onError(ws: WebSocket, error: Throwable): Unit = fail(Fault(ErrorCode.Disconnected, "Chromium CDP disconnected"))
  override def onClose(ws: WebSocket, status: Int, reason: String): CompletionStage[?] =
    fail(Fault(ErrorCode.Disconnected, "Chromium CDP closed"))
    CompletableFuture.completedFuture(null)

  def call(method: String, params: Json = Json.obj(), session: Option[String] = None, timeout: FiniteDuration = 15.seconds): IO[Json] = IO.defer {
    if closed.get() || socket == null then IO.raiseError(Fault(ErrorCode.Disconnected, "Browser connection is closed"))
    else
      val id = ids.incrementAndGet()
      val reply = new CompletableFuture[Json]()
      val request = Json.obj("id" -> id.asJson, "method" -> method.asJson, "params" -> params)
        .deepMerge(session.fold(Json.obj())(s => Json.obj("sessionId" -> s.asJson)))
      val action = IO(pending.put(id, reply)) *>
        IO.fromCompletableFuture(IO(socket.sendText(request.noSpaces, true))) *>
        IO.fromCompletableFuture(IO(reply))
      action.timeoutTo(timeout, IO.raiseError(Fault(ErrorCode.Timeout, s"CDP $method timed out")))
        .guarantee(IO(pending.remove(id)).void)
  }

  def close: IO[Unit] = IO {
    fail(Fault(ErrorCode.Disconnected, "Browser connection closed"))
    if socket != null then socket.abort()
  }

object Cdp:
  def resource(url: String, onEvent: Json => Unit): Resource[IO, Cdp] =
    Resource.make(IO(new Cdp(onEvent)).flatTap(_.connect(url)))(_.close)
