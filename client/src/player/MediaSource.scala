package htpc.client.player

import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global

enum MediaFormat:
  case File, Hls, Dash

/** URLs are resolved by the App, kept in memory, and never placed in navigation hashes/storage. */
final case class MediaSource(url: String, format: MediaFormat = MediaFormat.File) extends VideoSource:
  def create(host: dom.HTMLElement, report: VideoEvent => Unit): VideoBackend = new MediaBackend(host, this, report)

@js.native @JSGlobal("shaka.Player")
private class ShakaPlayer extends js.Object:
  def attach(video: dom.HTMLVideoElement): js.Promise[Unit] = js.native
  def load(url: String, start: Double, mime: String): js.Promise[Unit] = js.native
  def destroy(): js.Promise[Unit] = js.native
  def addEventListener(name: String, callback: js.Function1[js.Object, Unit]): Unit = js.native

private object ShakaLoader:
  private var loading: Option[Future[Unit]] = None
  def load(): Future[Unit] = loading.getOrElse {
    val promise = Promise[Unit]()
    val script = dom.document.createElement("script").asInstanceOf[dom.HTMLScriptElement]
    script.src = "/shaka-player.js?v=5.2.13"
    val timeout = dom.window.setTimeout(() => promise.tryFailure(RuntimeException("Media engine timeout")), 25000)
    script.onload = (_: dom.Event) => { dom.window.clearTimeout(timeout); promise.trySuccess(()); () }
    script.addEventListener("error", (_: dom.Event) => { dom.window.clearTimeout(timeout); promise.tryFailure(RuntimeException("Media engine unavailable")); () })
    loading = Some(promise.future)
    promise.future.failed.foreach { _ => loading = None; script.remove() }
    dom.document.head.appendChild(script)
    promise.future
  }

private final class MediaBackend(host: dom.HTMLElement, source: MediaSource, report: VideoEvent => Unit) extends VideoBackend:
  private val video = dom.document.createElement("video").asInstanceOf[dom.HTMLVideoElement]
  private var closed = false
  private var ready = false
  private var engine: Option[ShakaPlayer] = None
  private var listeners = Vector.empty[(String, js.Function1[dom.Event, Unit])]
  private def emit(event: VideoEvent): Unit = if !closed then report(event)
  private def listen(name: String)(action: => Unit): Unit =
    val handler: js.Function1[dom.Event, Unit] = _ => if !closed then action
    video.addEventListener(name, handler); listeners :+= name -> handler
  private def progress(): Unit = emit(VideoEvent.Progress(video.currentTime, Option.when(video.duration.isFinite && video.duration > 0)(video.duration)))
  private def loaded(): Unit =
    progress()
    if !ready then
      ready = true
      emit(VideoEvent.Ready(VideoCapabilities(seek = video.duration.isFinite && video.duration > 0)))
  def mount(): Unit =
    video.setAttribute("playsinline", "")
    video.setAttribute("aria-label", "视频画面")
    video.preload = "auto"; video.controls = false; video.tabIndex = -1
    listen("loadedmetadata")(if source.format == MediaFormat.File then loaded())
    listen("timeupdate")(progress())
    listen("durationchange")(progress())
    listen("playing")(emit(VideoEvent.Phase(PlaybackPhase.Playing)))
    listen("pause")(if !video.ended then emit(VideoEvent.Phase(PlaybackPhase.Paused)))
    listen("waiting")(emit(VideoEvent.Phase(PlaybackPhase.Buffering)))
    listen("ended")(emit(VideoEvent.Ended))
    listen("error")(emit(VideoEvent.Failed("视频加载或解码失败，请重试。")))
    host.appendChild(video)
    if source.format == MediaFormat.File then { video.src = source.url; video.load() }
    else ShakaLoader.load().flatMap { _ =>
      if closed then Future.successful(()) else
        val player = new ShakaPlayer
        engine = Some(player)
        player.addEventListener("error", (_: js.Object) => emit(VideoEvent.Failed("流媒体播放失败，请重试。")))
        player.attach(video).toFuture.flatMap { _ =>
          if closed then Future.successful(())
          else player.load(source.url, 0, if source.format == MediaFormat.Hls then "application/x-mpegurl" else "application/dash+xml").toFuture.map(_ => if !closed then loaded())
        }
    }.failed.foreach(_ => emit(VideoEvent.Failed("流媒体加载失败，请重试。")))
  def play(): Unit =
    video.play().foreach(_.toFuture.failed.foreach { error =>
      if !closed then
        val name = error match
          case js.JavaScriptException(value) => value.asInstanceOf[js.Dynamic].selectDynamic("name").asInstanceOf[js.UndefOr[String]].getOrElse("")
          case _ => ""
        if name == "NotAllowedError" then emit(VideoEvent.AutoplayBlocked)
        else if name != "AbortError" then emit(VideoEvent.Failed("视频无法开始播放，请重试。"))
    })
  def pause(): Unit = video.pause()
  def seek(seconds: Double): Unit = video.currentTime = seconds
  def volume(value: Double): Unit = video.volume = value
  def speed(value: Double): Unit = video.playbackRate = value
  def close(): Unit = if !closed then
    closed = true
    listeners.foreach((name, callback) => video.removeEventListener(name, callback)); listeners = Vector.empty
    video.pause()
    engine.foreach(_.destroy().toFuture.failed.foreach(_ => ())); engine = None
    video.removeAttribute("src"); video.load(); video.remove()
