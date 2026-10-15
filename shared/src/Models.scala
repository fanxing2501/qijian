package htpc.protocol

import io.circe.{Codec, Decoder, Encoder, Json}

/** Phantom resource kinds keep unrelated identifiers distinct on both platforms. */
final case class Id[K](value: String)
object Id:
  given [K]: Codec[Id[K]] = Codec.from(
    Decoder.decodeString.emap(s =>
      Either.cond(s.matches("[A-Za-z0-9_.:-]{1,160}"), Id[K](s), "Invalid resource identifier")),
    Encoder.encodeString.contramap(_.value)
  )

sealed trait ChromiumKind
sealed trait TabKind
sealed trait WineKind
type ChromiumId = Id[ChromiumKind]
type TabId = Id[TabKind]
type WineId = Id[WineKind]

private def enumCodec[A](values: Array[A]): Codec[A] = Codec.from(
  Decoder.decodeString.emap(s => values.find(_.toString == s).toRight(s"Unknown enum value: $s")),
  Encoder.encodeString.contramap(_.toString)
)

enum Module:
  case System, Chromium, Wine, Sway, Storage, Host
object Module:
  given Codec[Module] = enumCodec(values)

enum ErrorCode:
  case InvalidArgument, NotFound, Conflict, Unavailable, Timeout, Disconnected,
    Execution, Serialization, Protocol, Internal
object ErrorCode:
  given Codec[ErrorCode] = enumCodec(values)

trait DomainError:
  def code: ErrorCode
  def message: String

final case class ChromiumError(code: ErrorCode, message: String) extends DomainError derives Codec.AsObject
final case class WineError(code: ErrorCode, message: String) extends DomainError derives Codec.AsObject
final case class SwayError(code: ErrorCode, message: String) extends DomainError derives Codec.AsObject
final case class SystemError(code: ErrorCode, message: String) extends DomainError derives Codec.AsObject
final case class StorageError(code: ErrorCode, message: String) extends DomainError derives Codec.AsObject
final case class HostError(code: ErrorCode, message: String) extends DomainError derives Codec.AsObject
/** Named local services are configured on the host; requests never supply shell commands. */
final case class HostService(name: String, available: Boolean, state: String, detail: String, pid: Long) derives Codec.AsObject
final case class HostTarget(id: String, title: String, url: String, kind: String) derives Codec.AsObject
final case class StorageKey(namespace: String, key: String) derives Codec.AsObject
final case class StorageStamp(revision: String, present: Boolean) derives Codec.AsObject
final case class StorageEntry(value: Option[Json], revision: String):
  def stamp: StorageStamp = StorageStamp(revision, value.nonEmpty)
object StorageEntry:
  given Codec[StorageEntry] = Codec.from(Decoder.instance(c => for
    present <- c.get[Boolean]("present")
    revision <- c.get[String]("revision")
    value <- if present then c.get[Json]("value").map(Some(_)) else Right(None)
  yield StorageEntry(value, revision)), Encoder.instance(e => Json.obj(
    "present" -> Json.fromBoolean(e.value.nonEmpty), "value" -> e.value.getOrElse(Json.Null), "revision" -> Json.fromString(e.revision))))
/** A named isolated world in a server-side Chromium page. */
final case class BrowserWorld(tab: TabRef, name: String) derives Codec.AsObject
final case class ChromiumSocket(path: String) derives Codec.AsObject
final case class ProtocolError(message: String) derives Codec.AsObject

enum CallError[+E]:
  case Domain(error: E)
  case Transport(message: String) extends CallError[Nothing]
  case Protocol(message: String) extends CallError[Nothing]

final case class Done() derives Codec.AsObject
final case class Capability(available: Boolean, detail: String) derives Codec.AsObject
final case class Capabilities(chromium: Capability, wine: Capability, sway: Capability, epoch: String) derives Codec.AsObject

enum InstanceState:
  case Running, Failed
object InstanceState:
  given Codec[InstanceState] = enumCodec(values)

final case class ChromiumOptions(
  name: String = "Browser",
  profile: Option[String] = None,
  proxy: Option[String] = None,
  language: String = "zh-CN",
  width: Int = 1280,
  height: Int = 800
) derives Codec.AsObject

final case class ChromiumInstance(id: ChromiumId, name: String, profile: Option[String], state: InstanceState) derives Codec.AsObject
final case class TabRef(instance: ChromiumId, tab: TabId) derives Codec.AsObject
final case class TabInfo(ref: TabRef, title: String, url: String) derives Codec.AsObject

enum WaitUntil:
  case Started, DOMContentLoaded, Load
object WaitUntil:
  given Codec[WaitUntil] = enumCodec(values)

final case class Navigation(url: String, waitUntil: WaitUntil = WaitUntil.DOMContentLoaded, timeoutMs: Int = 30000) derives Codec.AsObject
final case class NavigationResult(url: String, title: String) derives Codec.AsObject
final case class EvalOptions(awaitPromise: Boolean = true, timeoutMs: Int = 15000) derives Codec.AsObject
enum JsValue derives Codec.AsObject:
  case Value(value: Json)
  case Undefined
final case class Screenshot(mimeType: String, base64: String) derives Codec.AsObject
final case class PageFrame(url: String, title: String, width: Int, height: Int, image: Screenshot) derives Codec.AsObject
final case class PointerPosition(x: Double, y: Double) derives Codec.AsObject
enum BrowserKey:
  case Enter, Tab, Backspace, Delete, Escape, ArrowUp, ArrowDown, ArrowLeft, ArrowRight, Home, End, SelectAll
object BrowserKey:
  given Codec[BrowserKey] = enumCodec(values)

/** Fields follow CDP's cookie model; optional partitionKey preserves CHIPS identity. */
final case class Cookie(
  name: String,
  value: String,
  domain: String,
  path: String,
  expires: Option[Double] = None,
  httpOnly: Boolean = false,
  secure: Boolean = false,
  sameSite: Option[String] = None,
  partitionKey: Option[Json] = None,
  priority: Option[String] = None
) derives Codec.AsObject
final case class CookieSelector(name: String, domain: String, path: String = "/", partitionKey: Option[Json] = None) derives Codec.AsObject

final case class WineOptions(name: String = "DLL worker", profile: Option[String] = None) derives Codec.AsObject
final case class WineInstance(id: WineId, name: String, profile: Option[String], state: InstanceState) derives Codec.AsObject
/** json-utf8-v1: an adapter export accepts UTF-8 JSON and returns UTF-8 JSON. */
final case class DllInvocation(
  dll: String,
  function: String,
  arguments: Json,
  freeFunction: Option[String] = None
) derives Codec.AsObject
final case class ExecuteOptions(timeoutMs: Int = 15000) derives Codec.AsObject

final case class WindowRef(session: String, container: Long) derives Codec.AsObject
final case class WindowInfo(ref: WindowRef, title: String, appId: Option[String], workspace: String, focused: Boolean, fullscreen: Boolean, pid: Option[Long] = None, application: Option[String] = None) derives Codec.AsObject
final case class OutputMode(width: Int, height: Int, refreshMilliHz: Int) derives Codec.AsObject
final case class OutputInfo(name: String, active: Boolean, power: Boolean, currentMode: Option[OutputMode], modes: Vector[OutputMode]) derives Codec.AsObject
final case class WorkspaceInfo(name: String, focused: Boolean, output: String) derives Codec.AsObject
final case class DisplaySnapshot(session: String, windows: Vector[WindowInfo], workspaces: Vector[WorkspaceInfo], outputs: Vector[OutputInfo]) derives Codec.AsObject

enum BrowserEventKind:
  case InstanceCreated, InstanceDeleted, InstanceFailed, TabCreated, TabChanged, TabDeleted
object BrowserEventKind:
  given Codec[BrowserEventKind] = enumCodec(values)
final case class BrowserEvent(kind: BrowserEventKind, instance: ChromiumId, tab: Option[TabId] = None) derives Codec.AsObject
enum WineEventKind:
  case InstanceCreated, InstanceDeleted, InstanceFailed, ExecutionCompleted
object WineEventKind:
  given Codec[WineEventKind] = enumCodec(values)
final case class WineEvent(kind: WineEventKind, instance: WineId) derives Codec.AsObject
final case class DisplayEvent(change: String) derives Codec.AsObject

sealed abstract class Topic[E](val module: Module)(using val codec: Codec[E])
object Topic:
  case object Chromium extends Topic[BrowserEvent](Module.Chromium)
  case object Wine extends Topic[WineEvent](Module.Wine)
  case object Sway extends Topic[DisplayEvent](Module.Sway)

final case class EventEnvelope(epoch: String, sequence: Long, module: Module, payload: Json) derives Codec.AsObject
final case class RpcRequest(id: String, op: String, args: Json = Json.obj()) derives Codec.AsObject
final case class RpcResponse(id: String, outcome: String, payload: Json) derives Codec.AsObject

final case class JsProgram[A](source: String, decoder: Decoder[A])
final case class DllFunction[I, O](dll: String, function: String, freeFunction: Option[String], encoder: Encoder[I], decoder: Decoder[O])
