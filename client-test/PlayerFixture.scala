package htpc.client.playertest

import com.raquo.laminar.api.L.*
import htpc.client.ApiClient
import htpc.client.player.*
import htpc.client.tv.*
import htpc.client.youtube.YouTubePlayer
import htpc.youtube.VideoId
import io.circe.{Codec, Decoder, Encoder}
import org.scalajs.dom
import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js
import scala.scalajs.js.annotation.*

/** Separate test entrypoint; never bundled into the deployed app. */
object PlayerFixture:
  private var first = Promise[VideoSource]()
  private var callbacks = Vector.empty[VideoEvent => Unit]
  private var created = Vector.empty[String]
  private var closed = 0
  private var attempts = 0
  private var aborted = false

  private def probe(name: String): VideoSource = new VideoSource:
    def create(host: dom.HTMLElement, report: VideoEvent => Unit): VideoBackend =
      created :+= name; callbacks :+= report
      new VideoBackend:
        def mount(): Unit = { host.textContent = name; report(VideoEvent.Ready(VideoCapabilities())); report(VideoEvent.Progress(0, Some(60))) }
        def play(): Unit = report(VideoEvent.Phase(PlaybackPhase.Playing))
        def pause(): Unit = report(VideoEvent.Phase(PlaybackPhase.Paused))
        def seek(value: Double): Unit = report(VideoEvent.Progress(value, Some(60)))
        def volume(value: Double): Unit = ()
        def speed(value: Double): Unit = ()
        def close(): Unit = closed += 1

  @JSExportTopLevel("settleFirstPart")
  def settleFirstPart(): Unit = { first.trySuccess(probe("late-first")); () }
  @JSExportTopLevel("oldPlayerEvent")
  def oldPlayerEvent(index: Int, error: Boolean): Unit = callbacks(index)(if error then VideoEvent.Failed("old error") else VideoEvent.Ended)
  @JSExportTopLevel("playerFixtureStats")
  def stats(): js.Object = js.Dynamic.literal(created = js.Array(created*), closed = closed, attempts = attempts, aborted = aborted)

  def main(args: Array[String]): Unit =
    val focus = new FocusManager
    val app = new RoutedApp:
      type Route = String
      val routeCodec = Codec.from(Decoder.decodeString, Encoder.encodeString)
      val metadata = AppMetadata(AppId("fixture"), "Fixture", ImageSource("", ""), None, Color("#000"), "")
      def feeds = LazyList.empty
      val modes = Vector("files", "resume", "hls", "dash", "race", "youtube")
      val mainPage = Page(ctx => div(modes.map(mode => button(ctx.focus.target(s"fixture:$mode", "fixture"), mode,
        onClick --> (_ => ctx.navigation.go(destination(mode)))))))
      def resolve(mode: String): Page =
        val request = mode match
          case "race" =>
            first = Promise[VideoSource](); callbacks = Vector.empty; created = Vector.empty; closed = 0; attempts = 0; aborted = false
            VideoRequest(Vector(
              VideoPart("first", "Slow first", signal => {
                signal.addEventListener("abort", (_: dom.Event) => aborted = true)
                first.future
              }),
              VideoPart("second", "Second", _ => Future.successful(probe("second"))),
              VideoPart("third", "Retry third", _ => {
                attempts += 1
                if attempts == 1 then Future.failed(RuntimeException("expired source")) else Future.successful(probe("third"))
              })))
          case "youtube" => VideoRequest(Vector(YouTubePlayer.part(VideoId("testvid0001"), "YouTube 1"), YouTubePlayer.part(VideoId("testvid0002"), "YouTube 2")))
          case "hls" | "dash" =>
            val format = if mode == "hls" then MediaFormat.Hls else MediaFormat.Dash
            val path = if mode == "hls" then "/media/hls/stream.m3u8" else "/media/dash/stream.mpd"
            VideoRequest(Vector(VideoPart(mode, mode, _ => Future.successful(MediaSource(path, format)))))
          case _ =>
            val parts = Vector.tabulate(3)(i => VideoPart(s"part-$i", s"Real media ${i + 1}", _ => Future.successful(MediaSource("/media/sample.webm"))))
            VideoRequest(parts, startIndex = if mode == "resume" then 1 else 0, startPosition = if mode == "resume" then 3 else 0)
        VideoPlayer.page(request)
    val navigation = new Navigator(new AppRegistry(Vector(app)), focus)
    val context = PageContext(new ApiClient, navigation, focus)
    val pad = new GamepadInput(focus, () => navigation.go(Destination.Home))
    val root = div(cls := "tv-shell",
      onMountCallback(_ => { focus.start(() => navigation.back()); navigation.start(); pad.start() }),
      button(focus.target("background", "background"), "Behind player"),
      child <-- navigation.current.signal.map {
        case Destination.Custom(_, _, page) => page.render(context)
        case _ => app.mainPage.render(context)
      })
    renderOnDomContentLoaded(dom.document.getElementById("app"), root)
