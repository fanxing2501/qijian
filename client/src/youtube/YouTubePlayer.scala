package htpc.client.youtube

import htpc.client.player.*
import htpc.youtube.{VideoId, YouTubeVideo}
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global

@js.native @JSGlobal("window")
private object YouTubeGlobal extends js.Object:
  var onYouTubeIframeAPIReady: js.Function0[Unit] = js.native

@js.native @JSGlobal("YT.Player")
private class NativePlayer(element: dom.Element, options: PlayerOptions) extends js.Object:
  def playVideo(): Unit = js.native
  def pauseVideo(): Unit = js.native
  def getCurrentTime(): Double = js.native
  def getDuration(): Double = js.native
  def seekTo(seconds: Double, allowSeekAhead: Boolean): Unit = js.native
  def setVolume(value: Double): Unit = js.native
  def setPlaybackRate(value: Double): Unit = js.native
  def destroy(): Unit = js.native

private trait PlayerOptions extends js.Object:
  val videoId: String
  val width: String
  val height: String
  val playerVars: PlayerVars
  val events: PlayerEvents
private trait PlayerVars extends js.Object:
  val autoplay: Int
  val playsinline: Int
  val controls: Int
  val disablekb: Int
  val origin: String
private trait PlayerEvents extends js.Object:
  val onReady: js.Function1[js.Object, Unit]
  val onStateChange: js.Function1[PlayerEvent, Unit]
  val onError: js.Function1[PlayerEvent, Unit]
  val onAutoplayBlocked: js.Function1[js.Object, Unit]
@js.native private trait PlayerEvent extends js.Object:
  val data: Int = js.native

private object PlayerLoader:
  private var loading: Option[Future[Unit]] = None
  def load(): Future[Unit] =
    val yt = dom.window.asInstanceOf[js.Dynamic].selectDynamic("YT")
    if !js.isUndefined(yt) && yt != null && js.typeOf(yt.selectDynamic("Player")) == "function" then Future.successful(())
    else begin()
  private def begin(): Future[Unit] = loading.getOrElse {
    val ready = Promise[Unit]()
    val timeout = dom.window.setTimeout(() => ready.tryFailure(RuntimeException("YouTube 播放器加载超时，请检查网络后重试。")), 25000)
    YouTubeGlobal.onYouTubeIframeAPIReady = () => { dom.window.clearTimeout(timeout); ready.trySuccess(()); () }
    val script = dom.document.createElement("script").asInstanceOf[dom.HTMLScriptElement]
    script.src = "https://www.youtube.com/iframe_api"
    script.addEventListener("error", (_: dom.Event) => { dom.window.clearTimeout(timeout); ready.tryFailure(RuntimeException("无法连接 YouTube 播放器。")); () })
    dom.document.head.appendChild(script)
    loading = Some(ready.future)
    ready.future.failed.foreach { _ => loading = None; script.remove() }
    ready.future
  }

object YouTubePlayer:
  def part(video: VideoId, title: String, key: String = ""): VideoPart =
    VideoPart(if key.nonEmpty then key else video.value, title, _ => Future.successful(YouTubeSource(video)))
  def request(videos: Vector[YouTubeVideo], startIndex: Int = 0): VideoRequest =
    VideoRequest(videos.zipWithIndex.map((v, i) => part(v.id, v.title, s"$i:${v.id.value}")), startIndex)

final case class YouTubeSource(video: VideoId) extends VideoSource:
  def create(host: dom.HTMLElement, report: VideoEvent => Unit): VideoBackend = new VideoBackend:
    private var player: Option[NativePlayer] = None
    private var closed = false
    private var timer = 0
    private def emit(event: VideoEvent): Unit = if !closed then report(event)
    private def progress(): Unit = player.foreach { p =>
      val duration = p.getDuration()
      emit(VideoEvent.Progress(p.getCurrentTime(), Option.when(duration.isFinite && duration > 0)(duration)))
    }
    def mount(): Unit = PlayerLoader.load().onComplete {
      case scala.util.Success(_) if !closed =>
        val node = dom.document.createElement("div")
        host.appendChild(node)
        player = Some(new NativePlayer(node, new PlayerOptions {
          val videoId = video.value
          val width = "100%"
          val height = "100%"
          val playerVars = new PlayerVars {
            val autoplay = 0; val playsinline = 1; val controls = 0; val disablekb = 1; val origin = dom.window.location.origin
          }
          val events = new PlayerEvents {
            val onReady: js.Function1[js.Object, Unit] = _ => if !closed then
              progress()
              timer = dom.window.setInterval(() => progress(), 500)
              emit(VideoEvent.Ready(VideoCapabilities()))
            val onStateChange: js.Function1[PlayerEvent, Unit] = event => if !closed then
              progress()
              event.data match
                case 0 => emit(VideoEvent.Ended)
                case 1 => emit(VideoEvent.Phase(PlaybackPhase.Playing))
                case 2 => emit(VideoEvent.Phase(PlaybackPhase.Paused))
                case 3 => emit(VideoEvent.Phase(PlaybackPhase.Buffering))
                case _ => ()
            val onAutoplayBlocked: js.Function1[js.Object, Unit] = _ => emit(VideoEvent.AutoplayBlocked)
            val onError: js.Function1[PlayerEvent, Unit] = event => emit(VideoEvent.Failed(event.data match {
              case 100 => "视频不可用、已删除，或需要在播放浏览器中登录 YouTube。"
              case 101 | 150 => "YouTube 不允许此视频嵌入播放。"
              case 153 => "YouTube 未接受当前播放器来源。"
              case code => s"YouTube 播放失败（$code），请重试。"
            }))
          }
        }))
      case scala.util.Failure(_) if !closed => emit(VideoEvent.Failed("无法连接 YouTube 播放器，请重试。"))
      case _ => ()
    }
    def play(): Unit = player.foreach(_.playVideo())
    def pause(): Unit = player.foreach(_.pauseVideo())
    def seek(seconds: Double): Unit = player.foreach(_.seekTo(seconds, true))
    def volume(value: Double): Unit = player.foreach(_.setVolume(value * 100))
    def speed(value: Double): Unit = player.foreach(_.setPlaybackRate(value))
    def close(): Unit = if !closed then
      closed = true; dom.window.clearInterval(timer); player.foreach(_.destroy()); player = None
