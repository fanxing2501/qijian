package htpc.client.tv

import com.raquo.laminar.api.L.*
import com.raquo.laminar.codecs.StringAsIsCodec
import htpc.client.ApiClient
import htpc.client.youtube.YouTubeApp
import htpc.client.steam.Steam
import org.scalajs.dom
import scala.concurrent.ExecutionContext.Implicits.global

final class ToolsApp extends App:
  val metadata = AppMetadata(AppId("tools"), "系统工具", ImageSource("/tools.svg", "系统工具"), None, Color("#7eb7be"), "浏览器、Wine 与显示设备")
  def feeds = LazyList.empty
  val mainPage = Page { context => div(cls := "tv-simple-page",
    div(cls := "tv-tool-symbol", Icon("grid")),
    span(cls := "tv-eyebrow", "BEHIND THE SCREEN"), h1("让一切，顺畅运行。"), p("浏览器、Windows 适配器与显示设备，都在这里。"),
    a(cls := "tv-button primary", href := "/console", context.focus.target("tools:console", "tools"), "打开能力工作台"),
    button(cls := "tv-button subtle", context.focus.target("tools:back", "tools"), "返回主页", onClick --> (_ => context.navigation.back()))) }

object TvShell:
  def mount(): Unit =
    val api = new ApiClient
    val youtube = new YouTubeApp(api)
    val steam = new Steam(api)
    val registry = new AppRegistry(Vector(youtube, steam, new ToolsApp))
    val focus = new FocusManager
    val navigation = new Navigator(registry, focus)
    val gamepad = new GamepadInput(focus, () => navigation.go(Destination.Home), () => !steam.native.gameActive.now())
    val context = PageContext(api, navigation, focus)
    val clock = Var("")
    var clockTimer = 0
    val unloading: scala.scalajs.js.Function1[dom.Event, Unit] = _ => { youtube.service.releaseOnUnload(); steam.service.close(); steam.native.close() }
    def updateClock(): Unit =
      val now = new scala.scalajs.js.Date()
      clock.set(f"${now.getHours().toInt}%02d:${now.getMinutes().toInt}%02d")

    def navClass(id: String) = navigation.current.signal.map { destination =>
      val active = destination match
        case Destination.Home => id == "home"
        case Destination.AppHome(app) => app.metadata.id.value == id
        case Destination.Custom(app, _, _) => app.metadata.id.value == id
      if active then "tv-nav active" else "tv-nav"
    }
    def home: HtmlElement = div(cls := "tv-home",
      sectionTag(cls := "tv-hero",
        div(cls := "tv-hero-art"), div(cls := "tv-hero-shade"),
        div(cls := "tv-hero-copy", span(cls := "tv-eyebrow", "MAKE YOURSELF AT HOME"),
          h1("你的客厅，", br(), span("整个世界。")),
          p("一段好视频，一场新冒险。", br(), "把时间留给你喜欢的事。"),
          div(cls := "tv-hero-actions",
            button(cls := "tv-button primary", focus.target("home:youtube", "hero"), htmlAttr("data-autofocus", StringAsIsCodec) := "true",
              Icon("play"), "打开 YouTube", onClick --> (_ => navigation.go(Destination.AppHome(youtube)))),
            button(cls := "tv-button glass", focus.target("home:steam", "hero"), Icon("gamepad"), "我的游戏库",
              onClick --> (_ => navigation.go(Destination.AppHome(steam)))),
            button(cls := "tv-button glass", focus.target("home:login", "hero"), "登录你的账号",
              onClick --> (_ => navigation.go(youtube.loginDestination)))
          )
        ),
        div(cls := "tv-hero-label", Icon("play"), span("A LITTLE ROOM TO EXPLORE", small("放慢脚步，看见更大的世界")))
      ),
      sectionTag(cls := "tv-apps", div(cls := "tv-section-title", h2("从这里开始"), small("YOUR APPS · 你的应用")),
        div(cls := "tv-app-track", registry.apps.map { app => button(
          cls := s"tv-app-tile ${app.metadata.id.value}", focus.target(s"app:${app.metadata.id.value}", "apps"),
          img(src := app.metadata.icon.url, alt := app.metadata.icon.alt),
          div(cls := "min-w-0", span(cls := "app-title", app.metadata.name), span(cls := "app-caption", app.metadata.id.value match
            case "youtube" => "视频、音乐与灵感"
            case "steam" => "下一段冒险，在你的库里"
            case _ => "管理你的 HTPC")),
          Icon("arrow"), onClick --> (_ => navigation.go(Destination.AppHome(app))))
        })
      ),
      div(cls := "tv-home-feeds", child <-- youtube.service.revision.signal.combineWith(steam.service.revision.signal).map(_ => div(registry.apps.flatMap(_.feeds.take(8).toVector).map(FeedRow.render(_, context)))))
    )

    val root = div(cls := "tv-shell",
      onMountCallback(_ => { api.connect(); focus.start(() => navigation.back()); navigation.start(); gamepad.start(); youtube.service.refreshAccount(); steam.service.refreshAccount(); steam.native.mount(context); updateClock(); clockTimer = dom.window.setInterval(() => updateClock(), 10000); dom.window.addEventListener("pagehide", unloading) }),
      onUnmountCallback(_ => { api.disconnect(); focus.stop(); navigation.stop(); gamepad.stop(); steam.native.close(); dom.window.clearInterval(clockTimer); dom.window.removeEventListener("pagehide", unloading) }),
      headerTag(cls := "tv-topbar",
        button(cls := "tv-brand", focus.target("nav:home", "nav"), title := "返回首页", onClick --> (_ => navigation.go(Destination.Home)),
          span(cls := "tv-brand-symbol", Icon("play")), "HTPC", small("NEXT")),
        navTag(
          htmlAttr("aria-label", StringAsIsCodec) := "主导航",
          button(cls <-- navClass("home"),
            focus.target("nav:discover", "nav"), "发现", onClick --> (_ => navigation.go(Destination.Home))),
          button(cls <-- navClass("youtube"), focus.target("nav:youtube", "nav"), "YouTube", onClick --> (_ => navigation.go(Destination.AppHome(youtube)))),
          button(cls <-- navClass("steam"), focus.target("nav:steam", "nav"), "Steam", onClick --> (_ => navigation.go(Destination.AppHome(steam))))
        ),
        div(cls := "tv-topbar-right",
          span(cls := "tv-connection", span(cls <-- api.connected.signal.map(c => if c then "tv-connection-dot" else "size-1.5 rounded-full bg-amber-400")),
            child.text <-- api.connected.signal.map(c => if c then "HTPC 在线" else "正在连接")),
          button(cls := "tv-round-button", title := "搜索 YouTube", htmlAttr("aria-label", StringAsIsCodec) := "搜索 YouTube", focus.target("nav:search", "nav"), Icon("search"), onClick --> (_ => navigation.go(youtube.searchDestination))),
          button(cls := "tv-avatar", title := "YouTube 账号", focus.target("nav:account", "nav"),
            child.text <-- youtube.service.auth.signal.map(_.avatarLabel), onClick --> (_ => navigation.go(youtube.loginDestination))),
          timeTag(child.text <-- clock.signal))
      ),
      mainTag(child <-- navigation.current.signal.map {
        case Destination.Home => home
        case Destination.AppHome(app) => app.mainPage.render(context)
        case Destination.Custom(_, _, page) => page.render(context)
      }),
      footerTag(cls := "tv-control-bar",
        div(cls := "tv-control-group",
          span(cls := "tv-control", span(cls := "tv-keycap", "A"), "确认"),
          span(cls := "tv-control", span(cls := "tv-keycap", "B"), "返回"),
          span(cls := "tv-control", span(cls := "tv-keycap wide", "↑ ↓ ← →"), "浏览"),
          span(cls := "tv-control hint-home", span(cls := "tv-keycap wide", "START"), "主页")),
        span(cls := "tv-gamepad-status", Icon("gamepad"), child.text <-- gamepad.connected.signal.map(c =>
          if c then "手柄已连接" else if gamepad.available then "手柄 / 键盘均可操作" else "手柄请使用 localhost 或 HTTPS")))
    )
    renderOnDomContentLoaded(dom.document.getElementById("app"), root)
