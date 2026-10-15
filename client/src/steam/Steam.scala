package htpc.client.steam

import com.raquo.laminar.api.L.*
import com.raquo.laminar.codecs.StringAsIsCodec
import htpc.client.ApiClient
import htpc.client.tv.*
import htpc.steam.*
import io.circe.Codec
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js
import scala.util.{Failure, Success}

enum SteamRoute derives Codec.AsObject:
  case Login, LocalLogin
  case Game(appId: Int)

final class Steam(api: ApiClient) extends RoutedApp:
  type Route = SteamRoute
  val routeCodec = summon[Codec[SteamRoute]]
  val service = new SteamService(api)
  val native = new NativeSteam(api)
  val metadata = AppMetadata(AppId("steam"), "Steam", ImageSource("/steam.svg", "Steam"), None,
    Color("#66c0f4"), "你的游戏库 · 按最近游玩排序")
  def loginDestination: Destination = destination(SteamRoute.Login)
  def localDestination: Destination = destination(SteamRoute.LocalLogin)
  private def played(game: SteamGame): String =
    if game.lastPlayed == 0 then "尚无游玩记录"
    else
      val date = new js.Date(game.lastPlayed.toDouble * 1000)
      f"最近游玩 ${date.getFullYear().toInt}%04d-${date.getMonth().toInt + 1}%02d-${date.getDate().toInt}%02d"
  private def hours(minutes: Long): String = if minutes < 60 then s"$minutes 分钟" else f"${minutes / 60d}%.1f 小时"
  private def card(game: SteamGame): FeedCard = FeedCard(CardId(game.appId.toString), game.name, played(game),
    ImageSource(game.cover, game.name, Some("/steam.svg")), Some(hours(game.playtimeMinutes)), destination(SteamRoute.Game(game.appId)))

  def feeds: LazyList[FeedItem] = LazyList(
    if service.account.now().nonEmpty then FeedItem(FeedId("steam:library"), "Steam · 我的游戏库", "最近游玩的排在最前，未玩过的排在末尾",
      CardLayout.Poster, service.source(card))
    else FeedItem(FeedId("steam:connect"), "Steam · 我的游戏库", "用手机 Steam App 扫码连接账号", CardLayout.Landscape,
      new FeedSource[FeedCard]:
        def open(): FeedSession[FeedCard] = new FeedSession[FeedCard]:
          def close(): Unit = ()
          def load(cursor: Option[Cursor], limit: Int) = Future.successful(Right(FeedBatch(Vector(
            FeedCard(CardId("login"), "扫码登录 Steam", "查看你拥有的全部游戏", ImageSource("/steam.svg", "连接 Steam"), None, loginDestination)), None)))
    )
  )

  val mainPage: Page = Page { context =>
    val actionError = Var("")
    val busy = Var(false)
    div(cls := "steam-page",
      div(cls := "steam-banner",
        div(cls := "min-w-0", span(cls := "tv-eyebrow", "YOUR NEXT ADVENTURE"),
          div(cls := "flex items-center gap-5", img(cls := "steam-brand", src := "/steam.svg", alt := "Steam"), h1("你的游戏世界。")),
          p("你的全部游戏，从最近的冒险开始。")),
        div(cls := "steam-account",
          strong(child.text <-- service.account.signal.combineWith(service.checking.signal).map((a, checking) =>
            a.map(_.account_name).getOrElse(if checking then "正在读取账号…" else "连接你的 Steam 账号"))),
          span(child.text <-- service.total.signal.map(_.fold("")(n => s"共 $n 款游戏"))),
          span(child.text <-- native.state.signal.map {
            case Some(s) if s.loggedIn => s"本机客户端：${s.accountName} 已登录"
            case Some(_) => "本机客户端：未登录，请另行扫码"
            case None => "本机客户端：连接中"
          }),
          button(cls := "tv-button primary", context.focus.target("steam:login", "steam-actions"),
            htmlAttr("data-autofocus", StringAsIsCodec) := "true",
            child.text <-- service.account.signal.map(a => if a.nonEmpty then "切换游戏库账号" else "扫码连接游戏库"),
            onClick --> (_ => context.navigation.go(loginDestination))),
          child <-- service.account.signal.map {
            case Some(_) => div(cls := "steam-actions",
              button(cls := "tv-button glass", context.focus.target("steam:refresh", "steam-actions"), "刷新游戏库", onClick --> (_ => service.refreshLibrary())),
              button(cls := "tv-button glass", context.focus.target("steam:local", "steam-actions"), "本机下载与运行", onClick --> (_ => context.navigation.go(localDestination))),
              button(cls := "tv-button subtle", context.focus.target("steam:signout", "steam-actions"), "退出登录", disabled <-- busy.signal,
                onClick --> { _ => busy.set(true); service.signOut().onComplete { result =>
                  busy.set(false); result.failed.foreach(e => actionError.set(e.getMessage))
                }}))
            case None => div()
          })),
      p(cls := "steam-message", child.text <-- actionError.signal),
      child <-- service.problem.signal.map {
        case Some(message) => div(cls := "tv-feed-error", span(message), button(cls := "tv-button subtle",
          context.focus.target("steam:account-retry", "steam-actions"), "重试", onClick --> (_ => service.refreshAccount())))
        case None => div()
      },
      child <-- service.revision.signal.map(_ => div(feeds.map(FeedRow.render(_, context)).toVector)))
  }

  def resolve(route: SteamRoute): Page = route match
    case SteamRoute.LocalLogin => Page(ctx => NativeSteamPage.login(native, ctx))
    case SteamRoute.Login => Page(ctx => SteamLogin.render(service, ctx, Destination.AppHome(this)))
    case SteamRoute.Game(id) => Page { context =>
      val game = Var(Option.empty[SteamGame])
      val message = Var("正在读取游戏信息…")
      var disposed = false
      div(cls := "steam-detail",
        onMountCallback(_ => service.library().onComplete {
          case Success(games) if !disposed =>
            game.set(games.find(_.appId == id)); message.set(if game.now().isEmpty then "此游戏不在当前账号的游戏库中。" else "")
          case Failure(e) if !disposed => message.set(e.getMessage)
          case _ => ()
        }),
        onUnmountCallback(_ => disposed = true),
        button(cls := "tv-button glass", context.focus.target("steam:detail-back", "steam-detail"), htmlAttr("data-autofocus", StringAsIsCodec) := "true", "← 返回游戏库", onClick --> (_ => context.navigation.back())),
        p(cls := "steam-message", child.text <-- message.signal),
        child <-- game.signal.map {
          case None => div()
          case Some(g) => div(cls := "steam-detail-body",
            img(src := g.cover, alt := g.name, onError --> { e =>
              val image = e.target.asInstanceOf[org.scalajs.dom.HTMLImageElement]
              if !image.src.endsWith("/steam.svg") then image.src = "/steam.svg"
            }),
            div(span(cls := "tv-eyebrow", "IN YOUR STEAM LIBRARY"), h1(g.name), p(played(g)),
               p(s"总游玩时长：${hours(g.playtimeMinutes)}"), p(s"最近两周：${hours(g.recentMinutes)}"),
               NativeSteamPage.controls(this, g.appId, context), small(s"AppID ${g.appId}")))
        })
    }
