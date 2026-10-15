package htpc.client.player

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success, Try}

enum PlaybackPhase:
  case Loading, Playing, Paused, Buffering, Ended, Failed

final case class VideoCapabilities(seek: Boolean = true, volume: Boolean = true, speed: Boolean = true)

enum VideoEvent:
  case Ready(capabilities: VideoCapabilities)
  case Phase(value: PlaybackPhase)
  case Progress(position: Double, duration: Option[Double])
  case Ended
  case Failed(message: String)
  case AutoplayBlocked

/** Backends report actual playback, never estimate EOF from a clock. */
trait VideoBackend:
  def mount(): Unit
  def play(): Unit
  def pause(): Unit
  def seek(seconds: Double): Unit
  def volume(value: Double): Unit
  def speed(value: Double): Unit
  def close(): Unit

/** A provider may implement its own playback engine without changing the player. */
trait VideoSource:
  def create(host: dom.HTMLElement, report: VideoEvent => Unit): VideoBackend

/** Only the current part is resolved. Credentials and signed-URL renewal belong to the calling App. */
final case class VideoPart(id: String, title: String, resolve: dom.AbortSignal => Future[VideoSource])
final case class VideoRequest(parts: Vector[VideoPart], startIndex: Int = 0, startPosition: Double = 0, autoNext: Boolean = true):
  require(parts.nonEmpty, "A video request needs at least one part")
  require(parts.map(_.id).distinct.size == parts.size, "Part IDs must be unique within a request")
  require(parts.indices.contains(startIndex), "Invalid starting part")
  require(startPosition.isFinite && startPosition >= 0, "Invalid starting position")

final case class PlaybackState(index: Int, phase: PlaybackPhase = PlaybackPhase.Loading,
  position: Double = 0, duration: Option[Double] = None, ready: Boolean = false,
  capabilities: VideoCapabilities = VideoCapabilities(), volume: Double = 1, speed: Double = 1,
  autoNext: Boolean = true, message: String = "")

/** A mounted page owns one controller. Generation fences isolate late resolutions and old engine events. */
final class Playback(val request: VideoRequest, host: dom.HTMLElement):
  val state = Var(PlaybackState(request.startIndex, autoNext = request.autoNext))
  private var generation = 0L
  private var closed = false
  private var backend: Option[VideoBackend] = None
  private var cancellation: Option[dom.AbortController] = None
  private var desiredPlaying = true
  private var ended = false
  private var loadTimer = 0

  private def release(): Unit =
    dom.window.clearTimeout(loadTimer)
    cancellation.foreach(_.abort()); cancellation = None
    backend.foreach(b => Try(b.close())); backend = None
    host.replaceChildren()

  private def fail(message: String): Unit =
    // Also fence a late onReady or Promise rejection from the failed backend.
    generation += 1
    release()
    state.update(_.copy(phase = PlaybackPhase.Failed, ready = false, message = message))

  private def command(action: VideoBackend => Unit): Unit = if !closed then
    Try(backend.foreach(action)).failed.foreach(_ => fail("播放操作失败，请重试。"))

  def mount(): Unit = load(request.startIndex, request.startPosition)
  def select(index: Int): Unit = if request.parts.indices.contains(index) then load(index, 0)
  def previous(): Unit = select(state.now().index - 1)
  def next(): Unit = select(state.now().index + 1)
  def retry(): Unit = load(state.now().index, if state.now().phase == PlaybackPhase.Ended then 0 else state.now().position)

  private def load(index: Int, position: Double): Unit = if !closed then
    generation += 1
    val ticket = generation
    release()
    ended = false; desiredPlaying = true
    val abort = new dom.AbortController
    cancellation = Some(abort)
    state.update(s => s.copy(index = index, phase = PlaybackPhase.Loading, position = position, duration = None, ready = false, message = ""))
    def current: Boolean = !closed && ticket == generation
    loadTimer = dom.window.setTimeout(() => if current then fail("播放加载超时，请重试。"), 45000)
    def report(event: VideoEvent): Unit = if current then event match
      case VideoEvent.Ready(caps) if !state.now().ready =>
        dom.window.clearTimeout(loadTimer)
        state.update(_.copy(ready = true, capabilities = caps))
        command { b =>
          if caps.volume then b.volume(state.now().volume)
          if caps.speed then b.speed(state.now().speed)
          if caps.seek && position > 0 then b.seek(position)
          if desiredPlaying then b.play() else b.pause()
        }
      case VideoEvent.Ready(_) => ()
      case VideoEvent.Progress(time, duration) =>
        if time.isFinite && time >= 0 then state.update(_.copy(position = time, duration = duration.filter(d => d.isFinite && d > 0)))
      case VideoEvent.Phase(phase) if !ended => state.update(_.copy(phase = phase, message = ""))
      case VideoEvent.Phase(_) => ()
      case VideoEvent.AutoplayBlocked =>
        desiredPlaying = false
        state.update(_.copy(phase = PlaybackPhase.Paused, message = "按 A / 播放继续观看。"))
      case VideoEvent.Failed(message) => fail(message)
      case VideoEvent.Ended if !ended =>
        ended = true
        state.update(_.copy(phase = PlaybackPhase.Ended))
        if state.now().autoNext && index + 1 < request.parts.size then select(index + 1)
      case VideoEvent.Ended => ()
    val resolved = Try(request.parts(index).resolve(abort.signal)).fold(Future.failed, identity)
    resolved.onComplete {
      case Success(source) if current =>
        Try {
          val created = source.create(host, report)
          backend = Some(created)
          created.mount()
        }.failed.foreach(_ => if current then fail("无法启动播放器，请重试。"))
      case Failure(_) if current => fail("无法加载这一 P 的片源，请重试。")
      case _ => ()
    }

  def play(): Unit =
    if state.now().phase == PlaybackPhase.Ended then retry()
    else { desiredPlaying = true; if state.now().ready then command(_.play()) }
  def pause(): Unit = { desiredPlaying = false; if state.now().ready then command(_.pause()) }
  def toggle(): Unit =
    val phase = state.now().phase
    if desiredPlaying && Set(PlaybackPhase.Playing, PlaybackPhase.Buffering, PlaybackPhase.Loading).contains(phase) then pause() else play()
  def seek(seconds: Double): Unit = if seconds.isFinite && state.now().ready && state.now().capabilities.seek then
    val target = math.max(0, state.now().duration.fold(seconds)(math.min(_, seconds)))
    val wasEnded = ended
    if wasEnded then
      ended = false; desiredPlaying = false
      state.update(_.copy(phase = PlaybackPhase.Paused))
    command { b => b.seek(target); if wasEnded then b.pause() }
  def seekBy(delta: Double): Unit = seek(state.now().position + delta)
  def setVolume(value: Double): Unit = if value.isFinite then
    val bounded = math.max(0, math.min(1, value))
    state.update(_.copy(volume = bounded))
    if state.now().ready && state.now().capabilities.volume then command(_.volume(bounded))
  def setSpeed(value: Double): Unit = if value.isFinite && value >= 0.25 && value <= 4 then
    state.update(_.copy(speed = value))
    if state.now().ready && state.now().capabilities.speed then command(_.speed(value))
  def setAutoNext(value: Boolean): Unit = state.update(_.copy(autoNext = value))
  def close(): Unit = if !closed then
    closed = true; generation += 1; release()
