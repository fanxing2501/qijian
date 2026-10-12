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

sealed abstract class ChromiumOp[A: Codec](name: String) extends Request[ChromiumError, A](Module.Chromium, name):
  final def accept[F[_]](i: Interpreter[F]) = i.chromium(this)
object ChromiumOp:
  case object ListInstance extends ChromiumOp[Vector[ChromiumInstance]]("instance.list")
  final case class NewInstance(options: ChromiumOptions) extends ChromiumOp[ChromiumInstance]("instance.new"):
    override def args = options.asJson
  final case class GetInstance(instance: ChromiumId) extends ChromiumOp[ChromiumInstance]("instance.get"):
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
  final case class GetCookies(instance: ChromiumId) extends ChromiumOp[Vector[Cookie]]("cookie.list"):
    override def args = Json.obj("instance" -> instance.asJson)
  final case class SetCookies(instance: ChromiumId, cookies: Vector[Cookie]) extends ChromiumOp[Done]("cookie.set"):
    override def args = Json.obj("instance" -> instance.asJson, "cookies" -> cookies.asJson)
  final case class DeleteCookies(instance: ChromiumId, selector: CookieSelector) extends ChromiumOp[Done]("cookie.delete"):
    override def args = Json.obj("instance" -> instance.asJson, "selector" -> selector.asJson)

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
    def pure[E, A](op: Request[E, A]) = Right(PackedRequest(op))
    val result = (module, wire.op) match
      case (Module.System, "capabilities") => pure(SystemOp.GetCapabilities)
      case (Module.Chromium, "instance.list") => pure(ChromiumOp.ListInstance)
      case (Module.Chromium, "instance.new") => wire.args.as[ChromiumOptions].map(o => PackedRequest(ChromiumOp.NewInstance(o)))
      case (Module.Chromium, "instance.get") => browser.map(x => PackedRequest(ChromiumOp.GetInstance(x)))
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
      case (Module.Chromium, "cookie.list") => browser.map(x => PackedRequest(ChromiumOp.GetCookies(x)))
      case (Module.Chromium, "cookie.set") => (for i <- browser; cs <- field[Vector[Cookie]]("cookies") yield PackedRequest(ChromiumOp.SetCookies(i, cs)))
      case (Module.Chromium, "cookie.delete") => (for i <- browser; s <- field[CookieSelector]("selector") yield PackedRequest(ChromiumOp.DeleteCookies(i, s)))
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
