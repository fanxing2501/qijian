package htpc.client.youtube

import com.raquo.laminar.api.L.*
import com.raquo.laminar.codecs.StringAsIsCodec
import htpc.client.ApiClient
import htpc.client.tv.*
import htpc.youtube.*
import io.circe.Codec
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js.URIUtils.encodeURIComponent

enum YouTubeRoute derives Codec.AsObject:
  case Home, Login
  case Search(query: String)
  case Watch(id: VideoId, title: String)

final class YouTubeApp(api: ApiClient) extends RoutedApp:
  type Route = YouTubeRoute
  val routeCodec = summon[Codec[YouTubeRoute]]
  val service = new YouTubeService(api)
  val metadata = AppMetadata(AppId("youtube"), "YouTube", ImageSource("/youtube.svg", "YouTube"),
    Some(ImageSource("/tv-landscape.svg", "暮色山峦")), Color("#ff403d"), "推荐、订阅与值得探索的视频")
  def loginDestination: Destination = destination(YouTubeRoute.Login)
  def searchDestination: Destination = destination(YouTubeRoute.Search(""))
  private def card(video: YouTubeVideo): FeedCard = FeedCard(CardId(video.id.value), video.title,
    Vector(Some(video.author), video.views).flatten.mkString(" · "), ImageSource(video.thumbnail, video.title, Some("/youtube.svg")), video.duration,
    destination(YouTubeRoute.Watch(video.id, video.title)))

  def feeds: LazyList[FeedItem] = LazyList(
    if service.signedIn then
      FeedItem(FeedId("youtube:home"), "来自你的订阅", "每个订阅频道的最新视频", CardLayout.Landscape, service.subscriptions(card))
    else FeedItem(FeedId("youtube:home"), "YouTube · 发现", "用手机连接账号后可查看订阅", CardLayout.Landscape,
      service.source("https://www.youtube.com/", card)),
    FeedItem(FeedId("youtube:explore"), "走向更大的世界", "YouTube · 自然与旅行", CardLayout.Landscape,
      service.source("https://www.youtube.com/results?search_query=nature+travel+4k", card))
  )

  val mainPage: Page = Page(home)
  def resolve(route: YouTubeRoute): Page = route match
    case YouTubeRoute.Home => mainPage
    case YouTubeRoute.Login => Page(ctx => DeviceLogin.render(service, ctx, Destination.AppHome(this)))
    case YouTubeRoute.Search(query) => Page(ctx => search(ctx, query))
    case YouTubeRoute.Watch(id, title) => Page(ctx => YouTubePlayerPage.render(ctx, id, title))

  private def account(context: PageContext): HtmlElement = div(cls := "yt-account-status",
    child <-- service.auth.signal.map {
      case YouTubeAuth.SignedIn(name, _) => span(cls := "yt-signed-in", "● ", name)
      case YouTubeAuth.Checking => span("正在读取账号状态…")
      case YouTubeAuth.SignedOut => span("未登录 · 可搜索并浏览公开视频")
      case YouTubeAuth.Failed(message) => span(message)
    },
    button(cls := "tv-button glass", context.focus.target("youtube:account", "youtube-header"),
      child.text <-- service.auth.signal.map { case YouTubeAuth.SignedIn(_, _) => "管理账号"; case _ => "登录" },
      onClick --> (_ => context.navigation.go(loginDestination)))
  )

  private def home(context: PageContext): HtmlElement = div(cls := "yt-app-page",
    div(cls := "yt-app-banner", div(span(cls := "tv-eyebrow", "WATCH SOMETHING GOOD"), img(cls := "yt-wordmark", src := "/youtube.svg", alt := "YouTube"),
      h1("总有值得一看的。"), p("喜欢的创作者、新的灵感，还有你的下一段好时光。")), account(context)),
    div(cls := "yt-app-actions",
      button(cls := "tv-button primary", context.focus.target("youtube:search", "youtube-actions"), htmlAttr("data-autofocus", StringAsIsCodec) := "true", Icon("search"), "搜索视频", onClick --> (_ => context.navigation.go(searchDestination))),
      Vector("音乐现场", "科技", "游戏", "旅行").map { query =>
        button(cls := "tv-button glass", context.focus.target(s"youtube:category:$query", "youtube-actions"), query,
          onClick --> (_ => context.navigation.go(destination(YouTubeRoute.Search(query)))))
      },
      button(cls := "tv-button subtle", context.focus.target("youtube:subscriptions", "youtube-actions"), "我的订阅",
        onClick --> (_ => context.navigation.go(destination(YouTubeRoute.Search("@subscriptions")))))
    ),
    child <-- service.revision.signal.map(_ => div(feeds.take(8).map(FeedRow.render(_, context)).toVector)),
    child <-- service.auth.signal.map {
      case YouTubeAuth.SignedIn(_, _) =>
        val status = Var("")
        div(cls := "yt-account-footer", button(cls := "tv-button subtle", context.focus.target("youtube:signout", "youtube-footer"), "退出此 HTPC 的 YouTube 账号",
          onClick --> (_ => { status.set("正在退出…"); service.signOut().onComplete {
            case scala.util.Success(_) => status.set("已退出")
            case scala.util.Failure(e) => status.set(e.getMessage)
          }})), span(child.text <-- status.signal))
      case _ => div()
    }
  )

  private def search(context: PageContext, initial: String): HtmlElement =
    val query = Var(if initial == "@subscriptions" then "" else initial)
    val keyboard = Var(initial.isEmpty)
    def submit(): Unit = if query.now().trim.nonEmpty then
      val value = query.now().trim
      val video = scala.util.Try(new org.scalajs.dom.URL(value)).toOption.flatMap { url =>
        if url.hostname == "youtu.be" then Some(url.pathname.stripPrefix("/").takeWhile(_ != '/'))
        else if Set("www.youtube.com", "youtube.com", "m.youtube.com").contains(url.hostname) then
          Option(url.searchParams.get("v")).orElse(url.pathname.split("/").toList match
            case "" :: ("shorts" | "embed") :: id :: _ => Some(id)
            case _ => None)
        else None
      }.filter(_.matches("[A-Za-z0-9_-]{11}"))
      context.navigation.go(destination(video.fold[YouTubeRoute](YouTubeRoute.Search(value))(id => YouTubeRoute.Watch(VideoId(id), "YouTube"))))
    div(cls := "yt-search-page",
      div(cls := "yt-page-heading", div(span(cls := "tv-eyebrow", "YOUTUBE"), h1(if initial == "@subscriptions" then "你的订阅" else "想看些什么？")),
        button(cls := "tv-button glass", context.focus.target("search:back", "search-heading"), "返回", onClick --> (_ => context.navigation.back()))),
      form(cls := "yt-search-form", onSubmit.preventDefault --> (_ => submit()),
        input(value <-- query.signal, placeholder := "搜索视频、创作者或直接粘贴 YouTube 链接", onInput.mapToValue --> query,
          onClick --> (_ => keyboard.set(true)),
          htmlAttr("aria-label", StringAsIsCodec) := "搜索 YouTube", context.focus.target("search:query", "search-form"), htmlAttr("data-autofocus", StringAsIsCodec) := "true"),
        button(typ := "submit", cls := "tv-button primary", context.focus.target("search:submit", "search-form"), "搜索"),
        button(typ := "button", cls := "tv-button glass", context.focus.target("search:keyboard", "search-form"), "屏幕键盘", onClick --> (_ => keyboard.update(!_)))),
      child <-- keyboard.signal.map(show => if show then ScreenKeyboard.render(query, context, () => submit()) else div()),
      if initial.isEmpty then div(cls := "yt-search-hints", p("从你感兴趣的话题开始"),
        Vector("自然纪录片", "爵士音乐", "科技", "独立游戏", "美食").map { q => button(cls := "tv-button glass", context.focus.target(s"search:hint:$q", "search-hints"), q,
          onClick --> (_ => context.navigation.go(destination(YouTubeRoute.Search(q))))) })
      else
        val url = if initial == "@subscriptions" then "https://www.youtube.com/feed/subscriptions"
          else "https://www.youtube.com/results?search_query=" + encodeURIComponent(initial)
        if initial == "@subscriptions" && !service.signedIn then
          div(cls := "yt-search-hints", p("连接 YouTube 账号后查看你的订阅。"), button(cls := "tv-button primary",
            context.focus.target("subscriptions:login", "subscriptions"), "用手机连接", onClick --> (_ => context.navigation.go(loginDestination))))
        else FeedRow.render(FeedItem(FeedId("youtube:search:" + initial), if initial == "@subscriptions" then "订阅频道最新视频" else s"搜索：$initial",
          "来自 YouTube 的实时内容", CardLayout.Landscape,
          if initial == "@subscriptions" then service.subscriptions(card) else service.source(url, card)), context)
    )
