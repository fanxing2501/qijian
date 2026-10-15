package htpc.client.youtube

import com.raquo.laminar.api.L.*
import htpc.client.tv.*
import htpc.youtube.VideoId
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
  def getPlayerState(): Int = js.native
  def getCurrentTime(): Double = js.native
  def seekTo(seconds: Double, allowSeekAhead: Boolean): Unit = js.native
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
  val origin: String
private trait PlayerEvents extends js.Object:
  val onReady: js.Function1[js.Object, Unit]
  val onError: js.Function1[PlayerError, Unit]
  val onAutoplayBlocked: js.Function1[js.Object, Unit]
@js.native private trait PlayerError extends js.Object:
  val data: Int = js.native

private object PlayerLoader:
  private var loading: Option[Future[Unit]] = None
  def load(): Future[Unit] = loading.getOrElse {
    val ready = Promise[Unit]()
    val timeout = dom.window.setTimeout(() => ready.tryFailure(RuntimeException("YouTube 播放器加载超时，请检查网络后重试。")), 25000)
    YouTubeGlobal.onYouTubeIframeAPIReady = () => { dom.window.clearTimeout(timeout); ready.trySuccess(()); () }
    val script = dom.document.createElement("script").asInstanceOf[dom.HTMLScriptElement]
    script.src = "https://www.youtube.com/iframe_api"
    script.addEventListener("error", (_: dom.Event) => { dom.window.clearTimeout(timeout); ready.tryFailure(RuntimeException("无法连接 YouTube 播放器。")); () })
    dom.document.head.appendChild(script)
    loading = Some(ready.future)
    ready.future.failed.foreach(_ => loading = None)
    ready.future
  }

object YouTubePlayerPage:
  def render(context: PageContext, video: VideoId, titleText: String): HtmlElement =
    val status = Var("正在加载 YouTube 官方播放器…")
    val ready = Var(false)
    var player: Option[NativePlayer] = None
    var disposed = false
    val host = div(cls := "yt-player-host")
    def buttonControl(id: String, text: String)(call: NativePlayer => Unit) = button(cls := "tv-button glass",
      context.focus.target(s"player:$id", "player-controls"), text, disabled <-- ready.signal.map(!_), onClick --> (_ => player.foreach(call)))
    div(cls := "yt-player-page",
      onMountCallback { _ =>
        PlayerLoader.load().onComplete {
          case scala.util.Success(_) if !disposed =>
            val node = dom.document.createElement("div")
            host.ref.appendChild(node)
            player = Some(new NativePlayer(node, new PlayerOptions {
              val videoId = video.value
              val width = "100%"
              val height = "100%"
              val playerVars = new PlayerVars { val autoplay = 1; val playsinline = 1; val origin = dom.window.location.origin }
              val events = new PlayerEvents {
                val onReady: js.Function1[js.Object, Unit] = _ => if !disposed then { ready.set(true); status.set("") }
                val onAutoplayBlocked: js.Function1[js.Object, Unit] = _ => if !disposed then status.set("点击播放器中的播放按钮开始观看。")
                val onError: js.Function1[PlayerError, Unit] = event => if !disposed then
                  ready.set(false)
                  status.set(event.data match {
                    case 100 => "视频不可用、已删除，或需要在播放浏览器中登录 YouTube。"
                    case 101 | 150 => "YouTube 拒绝了当前嵌入播放，请使用下方入口在 YouTube 打开。"
                    case 153 => "YouTube 未接受当前播放器来源，请在 YouTube 打开此视频。"
                    case code => s"YouTube 播放失败（$code），可在 YouTube 页面继续观看。"
                  })
              }
            }))
          case scala.util.Failure(error) if !disposed => status.set(error.getMessage)
          case _ => ()
        }
      },
      onUnmountCallback(_ => { disposed = true; player.foreach(_.destroy()); player = None }),
      div(cls := "yt-player-heading", button(cls := "tv-button glass", context.focus.target("player:back", "player-header"), "← 返回", onClick --> (_ => context.navigation.back())), h1(titleText)),
      host,
      div(cls := "yt-player-status", child.text <-- status.signal),
      div(cls := "yt-player-controls",
        buttonControl("rewind", "−10 秒")(p => p.seekTo(math.max(0, p.getCurrentTime() - 10), true)),
        buttonControl("toggle", "播放 / 暂停")(p => if p.getPlayerState() == 1 then p.pauseVideo() else p.playVideo()),
        buttonControl("forward", "+10 秒")(p => p.seekTo(p.getCurrentTime() + 10, true)),
        a(cls := "tv-button subtle", href := s"https://www.youtube.com/watch?v=${video.value}", target := "_blank", rel := "noopener noreferrer",
          context.focus.target("player:external", "player-controls"), "在 YouTube 打开 ↗")),
      p(cls := "yt-playback-note", "设备授权用于账号和订阅；官方播放器使用播放浏览器的独立会话。")
    )
