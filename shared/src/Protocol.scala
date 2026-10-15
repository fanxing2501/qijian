package htpc.protocol

import io.circe.{Codec, Decoder, Json}
import io.circe.syntax.*

private given [A: io.circe.Encoder: Decoder]: Codec[Vector[A]] =
  Codec.from(Decoder.decodeVector[A], io.circe.Encoder.encodeVector[A])
private given Codec[Json] = Codec.from(Decoder.decodeJson, io.circe.Encoder.encodeJson)

/** Each constructor carries evidence for BOTH its error and result types. */
sealed abstract class Request[E, A](val module: Module, val name: String)(using
  val errorCodec: Codec[E], val resultCodec: Codec[A]
):
  def args: Json = Json.obj()
  def accept[F[_]](interpreter: Interpreter[F]): F[Either[E, A]]
  final def wire(id: String): RpcRequest = RpcRequest(id, name, args)
  final def decode(response: RpcResponse): Either[CallError[E], A] = response.outcome match
    case "result" => resultCodec.decodeJson(response.payload).left.map(e => CallError.Protocol(e.getMessage))
    case "error" => errorCodec.decodeJson(response.payload)
      .left.map(e => CallError.Protocol(e.getMessage)).flatMap(e => Left(CallError.Domain(e)))
    case "protocol-error" => Left(CallError.Protocol(response.payload.hcursor.get[String]("message").getOrElse("Invalid protocol response")))
    case other => Left(CallError.Protocol(s"Unknown response outcome: $other"))

trait Interpreter[F[_]]:
  def chromium[A](op: ChromiumOp[A]): F[Either[ChromiumError, A]]
  def wine[A](op: WineOp[A]): F[Either[WineError, A]]
  def sway[A](op: SwayOp[A]): F[Either[SwayError, A]]
  def system[A](op: SystemOp[A]): F[Either[SystemError, A]]
  def storage[A](op: StorageOp[A]): F[Either[StorageError, A]]
  def host[A](op: HostOp[A]): F[Either[HostError, A]]

sealed abstract class ChromiumOp[A: Codec](name: String) extends Request[ChromiumError, A](Module.Chromium, name):
  final def accept[F[_]](i: Interpreter[F]) = i.chromium(this)
object ChromiumOp:
  case object ListInstance extends ChromiumOp[Vector[ChromiumInstance]]("instance.list")
  final case class NewInstance(options: ChromiumOptions) extends ChromiumOp[ChromiumInstance]("instance.new"):
    override def args = options.asJson
  final case class GetInstance(instance: ChromiumId) extends ChromiumOp[ChromiumInstance]("instance.get"):
    override def args = Json.obj("instance" -> instance.asJson)
  final case class GetSocket(instance: ChromiumId) extends ChromiumOp[ChromiumSocket]("instance.socket"):
    override def args = Json.obj("instance" -> instance.asJson)
  final case class DeleteInstance(instance: ChromiumId) extends ChromiumOp[Done]("instance.delete"):
    override def args = Json.obj("instance" -> instance.asJson)
  final case class ListTab(instance: ChromiumId) extends ChromiumOp[Vector[TabInfo]]("tab.list"):
    override def args = Json.obj("instance" -> instance.asJson)
  final case class NewTab(instance: ChromiumId) extends ChromiumOp[TabInfo]("tab.new"):
    override def args = Json.obj("instance" -> instance.asJson)
  final case class DeleteTab(tab: TabRef) extends ChromiumOp[Done]("tab.delete"):
    override def args = Json.obj("tab" -> tab.asJson)
  final case class Goto(tab: TabRef, navigation: Navigation) extends ChromiumOp[NavigationResult]("tab.goto"):
    override def args = Json.obj("tab" -> tab.asJson, "navigation" -> navigation.asJson)
  final case class Back(tab: TabRef) extends ChromiumOp[NavigationResult]("tab.back"):
    override def args = Json.obj("tab" -> tab.asJson)
  final case class Forward(tab: TabRef) extends ChromiumOp[NavigationResult]("tab.forward"):
    override def args = Json.obj("tab" -> tab.asJson)
  final case class Reload(tab: TabRef) extends ChromiumOp[NavigationResult]("tab.reload"):
    override def args = Json.obj("tab" -> tab.asJson)
  final case class EvalJS(tab: TabRef, script: String, options: EvalOptions) extends ChromiumOp[JsValue]("tab.eval"):
    override def args = Json.obj("tab" -> tab.asJson, "script" -> script.asJson, "options" -> options.asJson)
  final case class TakeScreenshot(tab: TabRef) extends ChromiumOp[Screenshot]("tab.screenshot"):
    override def args = Json.obj("tab" -> tab.asJson)
  final case class CapturePage(tab: TabRef) extends ChromiumOp[PageFrame]("tab.capture"):
    override def args = Json.obj("tab" -> tab.asJson)
  final case class ActivateTab(tab: TabRef) extends ChromiumOp[Done]("tab.activate"):
    override def args = Json.obj("tab" -> tab.asJson)
  final case class Click(tab: TabRef, position: PointerPosition) extends ChromiumOp[Done]("tab.click"):
    override def args = Json.obj("tab" -> tab.asJson, "position" -> position.asJson)
  final case class InsertText(tab: TabRef, text: String) extends ChromiumOp[Done]("tab.insertText"):
    override def args = Json.obj("tab" -> tab.asJson, "text" -> text.asJson)
  final case class PressKey(tab: TabRef, key: BrowserKey) extends ChromiumOp[Done]("tab.pressKey"):
    override def args = Json.obj("tab" -> tab.asJson, "key" -> key.asJson)
  final case class Scroll(tab: TabRef, deltaY: Double) extends ChromiumOp[Done]("tab.scroll"):
    override def args = Json.obj("tab" -> tab.asJson, "deltaY" -> deltaY.asJson)
  final case class GetCookies(instance: ChromiumId) extends ChromiumOp[Vector[Cookie]]("cookie.list"):
    override def args = Json.obj("instance" -> instance.asJson)
  final case class SetCookies(instance: ChromiumId, cookies: Vector[Cookie]) extends ChromiumOp[Done]("cookie.set"):
    override def args = Json.obj("instance" -> instance.asJson, "cookies" -> cookies.asJson)
  final case class DeleteCookies(instance: ChromiumId, selector: CookieSelector) extends ChromiumOp[Done]("cookie.delete"):
    override def args = Json.obj("instance" -> instance.asJson, "selector" -> selector.asJson)
  final case class ClearCookies(instance: ChromiumId) extends ChromiumOp[Done]("cookie.clear"):
    override def args = Json.obj("instance" -> instance.asJson)

sealed abstract class WineOp[A: Codec](name: String) extends Request[WineError, A](Module.Wine, name):
  final def accept[F[_]](i: Interpreter[F]) = i.wine(this)
object WineOp:
  case object ListInstance extends WineOp[Vector[WineInstance]]("instance.list")
  final case class NewInstance(options: WineOptions) extends WineOp[WineInstance]("instance.new"):
    override def args = options.asJson
  final case class GetInstance(instance: WineId) extends WineOp[WineInstance]("instance.get"):
    override def args = Json.obj("instance" -> instance.asJson)
  final case class DeleteInstance(instance: WineId) extends WineOp[Done]("instance.delete"):
    override def args = Json.obj("instance" -> instance.asJson)
  final case class Execute(instance: WineId, invocation: DllInvocation, options: ExecuteOptions) extends WineOp[Json]("execute"):
    override def args = Json.obj("instance" -> instance.asJson, "invocation" -> invocation.asJson, "options" -> options.asJson)

sealed abstract class SwayOp[A: Codec](name: String) extends Request[SwayError, A](Module.Sway, name):
  final def accept[F[_]](i: Interpreter[F]) = i.sway(this)
object SwayOp:
  case object Snapshot extends SwayOp[DisplaySnapshot]("snapshot")
  final case class Focus(window: WindowRef) extends SwayOp[Done]("window.focus"):
    override def args = Json.obj("window" -> window.asJson)
  final case class SetFullscreen(window: WindowRef, enabled: Boolean) extends SwayOp[Done]("window.fullscreen"):
    override def args = Json.obj("window" -> window.asJson, "enabled" -> enabled.asJson)
  final case class Move(window: WindowRef, workspace: String) extends SwayOp[Done]("window.move"):
    override def args = Json.obj("window" -> window.asJson, "workspace" -> workspace.asJson)
  final case class Close(window: WindowRef) extends SwayOp[Done]("window.close"):
    override def args = Json.obj("window" -> window.asJson)
  final case class ActivateWorkspace(workspace: String) extends SwayOp[Done]("workspace.activate"):
    override def args = Json.obj("workspace" -> workspace.asJson)
  final case class SetOutputPower(output: String, enabled: Boolean) extends SwayOp[Done]("output.power"):
    override def args = Json.obj("output" -> output.asJson, "enabled" -> enabled.asJson)
  final case class SetOutputMode(output: String, mode: OutputMode) extends SwayOp[Done]("output.mode"):
    override def args = Json.obj("output" -> output.asJson, "mode" -> mode.asJson)

sealed abstract class SystemOp[A: Codec](name: String) extends Request[SystemError, A](Module.System, name):
  final def accept[F[_]](i: Interpreter[F]) = i.system(this)
object SystemOp:
  case object GetCapabilities extends SystemOp[Capabilities]("capabilities")
  case object GameDisplayStatus extends SystemOp[GameDisplaySupport]("game-display.status")
  final case class PrepareGameDisplay(application: String, settings: GameDisplaySettings) extends SystemOp[GameDisplayLaunch]("game-display.prepare"):
    override def args = Json.obj("application" -> application.asJson, "settings" -> settings.asJson)
  final case class QrCode(text: String) extends SystemOp[Screenshot]("qr"):
    override def args = Json.obj("text" -> text.asJson)

/** Provider-neutral, versioned server storage. Browser transfers never expose values to the UI. */
sealed abstract class StorageOp[A: Codec](name: String) extends Request[StorageError, A](Module.Storage, name):
  final def accept[F[_]](i: Interpreter[F]) = i.storage(this)
object StorageOp:
  final case class Get(key: StorageKey) extends StorageOp[StorageEntry]("get"):
    override def args = Json.obj("key" -> key.asJson)
  final case class Inspect(key: StorageKey) extends StorageOp[StorageStamp]("inspect"):
    override def args = Json.obj("key" -> key.asJson)
  final case class Put(key: StorageKey, value: Json, expected: Option[String] = None) extends StorageOp[StorageStamp]("put"):
    override def args = Json.obj("key" -> key.asJson, "value" -> value, "expected" -> expected.asJson)
  final case class Delete(key: StorageKey, expected: Option[String] = None) extends StorageOp[StorageStamp]("delete"):
    override def args = Json.obj("key" -> key.asJson, "expected" -> expected.asJson)
  final case class ReadInto(key: StorageKey, world: BrowserWorld, binding: String) extends StorageOp[StorageStamp]("readInto"):
    override def args = Json.obj("key" -> key.asJson, "world" -> world.asJson, "binding" -> binding.asJson)
  final case class WriteFrom(key: StorageKey, world: BrowserWorld, expression: String, expected: String) extends StorageOp[StorageStamp]("writeFrom"):
    override def args = Json.obj("key" -> key.asJson, "world" -> world.asJson, "expression" -> expression.asJson, "expected" -> expected.asJson)
  final case class CaptureCdp(key: StorageKey, instance: ChromiumId, method: String, params: Json, expected: String) extends StorageOp[StorageStamp]("captureCdp"):
    override def args = Json.obj("key" -> key.asJson, "instance" -> instance.asJson, "method" -> method.asJson, "params" -> params, "expected" -> expected.asJson)

/** Control configured user services and bridge their local Chromium/CEF targets. */
sealed abstract class HostOp[A: Codec](name: String) extends Request[HostError, A](Module.Host, name):
  final def accept[F[_]](i: Interpreter[F]) = i.host(this)
object HostOp:
  final case class Status(service: String) extends HostOp[HostService]("service.status"):
    override def args = Json.obj("service" -> service.asJson)
  final case class Start(service: String) extends HostOp[HostService]("service.start"):
    override def args = Json.obj("service" -> service.asJson)
  final case class Stop(service: String) extends HostOp[HostService]("service.stop"):
    override def args = Json.obj("service" -> service.asJson)
  final case class Targets(service: String) extends HostOp[Vector[HostTarget]]("cdp.targets"):
    override def args = Json.obj("service" -> service.asJson)
  final case class Socket(service: String, target: String) extends HostOp[ChromiumSocket]("cdp.socket"):
    override def args = Json.obj("service" -> service.asJson, "target" -> target.asJson)

/** Existential request plus its witnesses. No Any, casts, or client-supplied result tags. */
trait PackedRequest:
  type E
  type A
  val request: Request[E, A]
object PackedRequest:
  def apply[E0, A0](value: Request[E0, A0]): PackedRequest = new PackedRequest:
    type E = E0
    type A = A0
    val request = value

object Protocol:
  def decode(module: Module, wire: RpcRequest): Either[ProtocolError, PackedRequest] =
    val c = wire.args.hcursor
    def field[A: Decoder](name: String) = c.get[A](name)
    def tab = field[TabRef]("tab")
    def browser = field[ChromiumId]("instance")
    def wine = field[WineId]("instance")
    def window = field[WindowRef]("window")
    def key = field[StorageKey]("key")
    def pure[E, A](op: Request[E, A]) = Right(PackedRequest(op))
    val result = (module, wire.op) match
      case (Module.Host, "service.status") => field[String]("service").map(s => PackedRequest(HostOp.Status(s)))
      case (Module.Host, "service.start") => field[String]("service").map(s => PackedRequest(HostOp.Start(s)))
      case (Module.Host, "service.stop") => field[String]("service").map(s => PackedRequest(HostOp.Stop(s)))
      case (Module.Host, "cdp.targets") => field[String]("service").map(s => PackedRequest(HostOp.Targets(s)))
      case (Module.Host, "cdp.socket") => (for s <- field[String]("service"); t <- field[String]("target") yield PackedRequest(HostOp.Socket(s, t)))
      case (Module.System, "capabilities") => pure(SystemOp.GetCapabilities)
      case (Module.System, "game-display.status") => pure(SystemOp.GameDisplayStatus)
      case (Module.System, "game-display.prepare") => (for a <- field[String]("application"); s <- field[GameDisplaySettings]("settings") yield PackedRequest(SystemOp.PrepareGameDisplay(a, s)))
      case (Module.System, "qr") => field[String]("text").map(t => PackedRequest(SystemOp.QrCode(t)))
      case (Module.Storage, "get") => key.map(k => PackedRequest(StorageOp.Get(k)))
      case (Module.Storage, "inspect") => key.map(k => PackedRequest(StorageOp.Inspect(k)))
      case (Module.Storage, "put") => (for k <- key; v <- field[Json]("value"); e <- field[Option[String]]("expected") yield PackedRequest(StorageOp.Put(k, v, e)))
      case (Module.Storage, "delete") => (for k <- key; e <- field[Option[String]]("expected") yield PackedRequest(StorageOp.Delete(k, e)))
      case (Module.Storage, "readInto") => (for k <- key; w <- field[BrowserWorld]("world"); b <- field[String]("binding") yield PackedRequest(StorageOp.ReadInto(k, w, b)))
      case (Module.Storage, "writeFrom") => (for k <- key; w <- field[BrowserWorld]("world"); x <- field[String]("expression"); e <- field[String]("expected") yield PackedRequest(StorageOp.WriteFrom(k, w, x, e)))
      case (Module.Storage, "captureCdp") => (for k <- key; b <- browser; m <- field[String]("method"); p <- field[Json]("params"); e <- field[String]("expected") yield PackedRequest(StorageOp.CaptureCdp(k, b, m, p, e)))
      case (Module.Chromium, "instance.list") => pure(ChromiumOp.ListInstance)
      case (Module.Chromium, "instance.new") => wire.args.as[ChromiumOptions].map(o => PackedRequest(ChromiumOp.NewInstance(o)))
      case (Module.Chromium, "instance.get") => browser.map(x => PackedRequest(ChromiumOp.GetInstance(x)))
      case (Module.Chromium, "instance.socket") => browser.map(x => PackedRequest(ChromiumOp.GetSocket(x)))
      case (Module.Chromium, "instance.delete") => browser.map(x => PackedRequest(ChromiumOp.DeleteInstance(x)))
      case (Module.Chromium, "tab.list") => browser.map(x => PackedRequest(ChromiumOp.ListTab(x)))
      case (Module.Chromium, "tab.new") => browser.map(x => PackedRequest(ChromiumOp.NewTab(x)))
      case (Module.Chromium, "tab.delete") => tab.map(x => PackedRequest(ChromiumOp.DeleteTab(x)))
      case (Module.Chromium, "tab.goto") => (for t <- tab; n <- field[Navigation]("navigation") yield PackedRequest(ChromiumOp.Goto(t, n)))
      case (Module.Chromium, "tab.back") => tab.map(x => PackedRequest(ChromiumOp.Back(x)))
      case (Module.Chromium, "tab.forward") => tab.map(x => PackedRequest(ChromiumOp.Forward(x)))
      case (Module.Chromium, "tab.reload") => tab.map(x => PackedRequest(ChromiumOp.Reload(x)))
      case (Module.Chromium, "tab.eval") => (for t <- tab; s <- field[String]("script"); o <- field[EvalOptions]("options") yield PackedRequest(ChromiumOp.EvalJS(t, s, o)))
      case (Module.Chromium, "tab.screenshot") => tab.map(x => PackedRequest(ChromiumOp.TakeScreenshot(x)))
      case (Module.Chromium, "tab.capture") => tab.map(x => PackedRequest(ChromiumOp.CapturePage(x)))
      case (Module.Chromium, "tab.activate") => tab.map(x => PackedRequest(ChromiumOp.ActivateTab(x)))
      case (Module.Chromium, "tab.click") => (for t <- tab; p <- field[PointerPosition]("position") yield PackedRequest(ChromiumOp.Click(t, p)))
      case (Module.Chromium, "tab.insertText") => (for t <- tab; s <- field[String]("text") yield PackedRequest(ChromiumOp.InsertText(t, s)))
      case (Module.Chromium, "tab.pressKey") => (for t <- tab; k <- field[BrowserKey]("key") yield PackedRequest(ChromiumOp.PressKey(t, k)))
      case (Module.Chromium, "tab.scroll") => (for t <- tab; d <- field[Double]("deltaY") yield PackedRequest(ChromiumOp.Scroll(t, d)))
      case (Module.Chromium, "cookie.list") => browser.map(x => PackedRequest(ChromiumOp.GetCookies(x)))
      case (Module.Chromium, "cookie.set") => (for i <- browser; cs <- field[Vector[Cookie]]("cookies") yield PackedRequest(ChromiumOp.SetCookies(i, cs)))
      case (Module.Chromium, "cookie.delete") => (for i <- browser; s <- field[CookieSelector]("selector") yield PackedRequest(ChromiumOp.DeleteCookies(i, s)))
      case (Module.Chromium, "cookie.clear") => browser.map(x => PackedRequest(ChromiumOp.ClearCookies(x)))
      case (Module.Wine, "instance.list") => pure(WineOp.ListInstance)
      case (Module.Wine, "instance.new") => wire.args.as[WineOptions].map(o => PackedRequest(WineOp.NewInstance(o)))
      case (Module.Wine, "instance.get") => wine.map(x => PackedRequest(WineOp.GetInstance(x)))
      case (Module.Wine, "instance.delete") => wine.map(x => PackedRequest(WineOp.DeleteInstance(x)))
      case (Module.Wine, "execute") => (for i <- wine; call <- field[DllInvocation]("invocation"); o <- field[ExecuteOptions]("options") yield PackedRequest(WineOp.Execute(i, call, o)))
      case (Module.Sway, "snapshot") => pure(SwayOp.Snapshot)
      case (Module.Sway, "window.focus") => window.map(x => PackedRequest(SwayOp.Focus(x)))
      case (Module.Sway, "window.fullscreen") => (for w <- window; e <- field[Boolean]("enabled") yield PackedRequest(SwayOp.SetFullscreen(w, e)))
      case (Module.Sway, "window.move") => (for w <- window; s <- field[String]("workspace") yield PackedRequest(SwayOp.Move(w, s)))
      case (Module.Sway, "window.close") => window.map(x => PackedRequest(SwayOp.Close(x)))
      case (Module.Sway, "workspace.activate") => field[String]("workspace").map(x => PackedRequest(SwayOp.ActivateWorkspace(x)))
      case (Module.Sway, "output.power") => (for o <- field[String]("output"); e <- field[Boolean]("enabled") yield PackedRequest(SwayOp.SetOutputPower(o, e)))
      case (Module.Sway, "output.mode") => (for o <- field[String]("output"); m <- field[OutputMode]("mode") yield PackedRequest(SwayOp.SetOutputMode(o, m)))
      case _ => Left(io.circe.DecodingFailure(s"Unknown operation ${module.toString}/${wire.op}", c.history))
    result.left.map(e => ProtocolError(e.getMessage))
