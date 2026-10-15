package htpc.client

import com.raquo.laminar.api.L.*
import htpc.protocol.*
import io.circe.parser.{decode, parse}
import io.circe.syntax.*
import org.scalajs.dom
import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.util.control.NonFatal

final class ApiClient(using ExecutionContext):
  private val updates = new EventBus[EventEnvelope]
  private val syncs = new EventBus[Unit]
  val connected = Var(false)
  private var source: Option[dom.EventSource] = None
  private val clientId = scala.util.Random.alphanumeric.take(24).mkString
  private var nextId = 0L

  def call[E, A](request: Request[E, A]): Future[Either[CallError[E], A]] =
    nextId += 1
    val id = s"$clientId-$nextId"
    val controller = new dom.AbortController()
    val timer = dom.window.setTimeout(() => controller.abort(), 150000)
    val init = new dom.RequestInit:
      method = dom.HttpMethod.POST
      headers = js.Dictionary("Content-Type" -> "application/json")
      body = request.wire(id).asJson.noSpaces
      signal = controller.signal
    dom.fetch(s"/api/v1/${request.module.toString.toLowerCase}", init).toFuture
      .flatMap(_.text().toFuture).map { text =>
        decode[RpcResponse](text).left.map(e => CallError.Protocol(e.getMessage)).flatMap { response =>
          if response.id != id then Left(CallError.Protocol("Response request id does not match"))
          else request.decode(response)
        }
      }.recover { case NonFatal(error) => Left(CallError.Transport(Option(error.getMessage).getOrElse("HTTP request failed"))) }
      .andThen { case _ => dom.window.clearTimeout(timer) }

  def eval[A](tab: TabRef, program: JsProgram[A], options: EvalOptions = EvalOptions()): Future[Either[CallError[ChromiumError], A]] =
    call(ChromiumOp.EvalJS(tab, program.source, options)).map(_.flatMap {
      case JsValue.Value(json) => program.decoder.decodeJson(json).left.map(e => CallError.Protocol(e.getMessage))
      case JsValue.Undefined => Left(CallError.Domain(ChromiumError(ErrorCode.Serialization, "JavaScript returned undefined")))
    })

  def execute[I, O](instance: WineId, function: DllFunction[I, O], input: I, options: ExecuteOptions = ExecuteOptions()): Future[Either[CallError[WineError], O]] =
    call(WineOp.Execute(instance, DllInvocation(function.dll, function.function, function.encoder(input), function.freeFunction), options))
      .map(_.flatMap(json => function.decoder.decodeJson(json).left.map(e => CallError.Protocol(e.getMessage))))

  val resync: EventStream[Unit] = syncs.events
  def events[E](topic: Topic[E]): EventStream[Either[ProtocolError, E]] =
    updates.events.filter(_.module == topic.module).map(e => topic.codec.decodeJson(e.payload).left.map(err => ProtocolError(err.getMessage)))

  def connect(): Unit =
    disconnect()
    val s = new dom.EventSource("/api/v1/events")
    s.addEventListener("sync", (_: dom.MessageEvent) => { connected.set(true); syncs.writer.onNext(()) })
    s.addEventListener("change", (event: dom.MessageEvent) => {
      decode[EventEnvelope](event.data.toString).foreach(updates.writer.onNext)
    })
    s.onerror = (_: dom.Event) => connected.set(false)
    source = Some(s)

  def disconnect(): Unit =
    source.foreach(_.close())
    source = None
    connected.set(false)

  /** A small typed cleanup request can outlive the page that owns the tab. */
  def releaseOnUnload[E, A](request: Request[E, A]): Unit =
    nextId += 1
    val body = new dom.Blob(js.Array(request.wire(s"$clientId-$nextId").asJson.noSpaces),
      new dom.BlobPropertyBag { `type` = "application/json" })
    dom.window.navigator.sendBeacon(s"/api/v1/${request.module.toString.toLowerCase}", body)
