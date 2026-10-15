package htpc.client.steam

import com.raquo.laminar.api.L.*
import com.raquo.laminar.codecs.StringAsIsCodec
import htpc.client.tv.*
import htpc.protocol.*
import htpc.client.accounts.*
import org.scalajs.dom
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Success, Failure}

object SteamLogin:
  def render(service: SteamService, context: PageContext, home: Destination): HtmlElement =
    val code = Var(Option.empty[DeviceAuthorization])
    val status = Var("正在获取 Steam 登录二维码…")
    val busy = Var(false)
    val complete = Var(false)
    val seconds = Var(0)
    var disposed = false
    var timer = 0
    var clock = 0
    var generation = 0
    def cancel(): Unit =
      generation += 1
      dom.window.clearTimeout(timer)
      code.now().foreach(c => service.request(AuthOp.Cancel(c.id)).failed.foreach(_ => ()))
      code.set(None)
    def schedule(ms: Int, run: Int): Unit = timer = dom.window.setTimeout(() => poll(run), ms)
    def show(value: DeviceAuthorization): Unit =
      code.set(Some(value)); seconds.set(math.max(0, ((value.expiresAt - scala.scalajs.js.Date.now().toLong) / 1000).toInt))
    def poll(run: Int): Unit = if !disposed && generation == run then code.now().foreach { value =>
      if scala.scalajs.js.Date.now() >= value.expiresAt.toDouble then
        cancel(); status.set("二维码已过期，请按 A 重新获取。")
      else service.request(AuthOp.Poll(value.id)).onComplete { result => if !disposed && generation == run then result match
        case Success(AuthorizationProgress.Pending(ms)) => schedule(ms, run)
        case Success(AuthorizationProgress.QrPending(updated, scanned)) =>
          show(updated); status.set(if scanned then "已扫码，请在手机 Steam App 中确认登录。" else "等待扫码，请打开手机 Steam App 的 Steam 令牌。")
          schedule(updated.intervalMs, run)
        case Success(AuthorizationProgress.Authorized) =>
          code.set(None); complete.set(true); status.set("Steam 登录成功，正在同步账号…")
          service.refreshAccount().foreach { _ => if !disposed then
            status.set(service.problem.now().getOrElse("Steam 登录成功，可以查看游戏库了。"))
            context.focus.restore(context.navigation.key(context.navigation.current.now()))
          }
        case Success(AuthorizationProgress.Expired) => code.set(None); status.set("二维码已过期，请重新获取。")
        case Success(AuthorizationProgress.Denied(message)) => code.set(None); status.set(message)
        case Failure(e) => status.set(e.getMessage + " 正在重试…"); schedule(math.max(10000, value.intervalMs), run)
      }
    }
    def start(): Unit = if !busy.now() then
      cancel(); val run = generation
      busy.set(true); complete.set(false); status.set("正在获取 Steam 登录二维码…")
      service.request(AuthOp.Start(Providers.Steam)).onComplete {
        case Success(value) if disposed || generation != run => service.request(AuthOp.Cancel(value.id)).failed.foreach(_ => ())
        case Success(value) =>
          busy.set(false); show(value); status.set("请用手机 Steam App 扫描二维码，并确认登录此 HTPC。")
          schedule(value.intervalMs, run)
        case Failure(e) if !disposed && generation == run => busy.set(false); status.set(e.getMessage)
        case _ => ()
      }
    div(cls := "steam-login",
      onMountCallback { _ =>
        start()
        clock = dom.window.setInterval(() => code.now().foreach(v => seconds.set(math.max(0, ((v.expiresAt - scala.scalajs.js.Date.now().toLong) / 1000).toInt))), 1000)
      },
      onUnmountCallback(_ => { disposed = true; cancel(); dom.window.clearInterval(clock) }),
      div(cls := "yt-page-heading", div(span(cls := "tv-eyebrow", "STEAM · SIGN IN ON YOUR PHONE"), h1("扫码连接你的游戏世界"),
        p("打开手机 Steam App → Steam 令牌 → 扫描二维码。"))),
      div(cls := "yt-device-content",
        child <-- code.signal.map {
          case Some(value) => div(cls := "yt-device-pairing steam-pairing",
            div(cls := "yt-device-qr", img(src := value.qrImage, alt := "Steam 登录二维码")),
            div(cls := "yt-device-steps", h2("1. 用 Steam App 扫码"), p("在手机上确认登录 HTPC Next。"),
              h2("2. 回到这里，浏览全部游戏"), p("登录完成后页面会自动更新。"),
              span(cls := "yt-device-expiry", child.text <-- seconds.signal.map(s => f"本次登录剩余 ${s / 60}%02d:${s % 60}%02d"))))
          case None => div(cls := "yt-device-placeholder", child.text <-- complete.signal.map(done => if done then "✓" else "◎"))
        },
        p(cls := "yt-device-status steam-login-status", child.text <-- status.signal),
        div(cls := "yt-device-actions",
          button(cls := "tv-button primary", context.focus.target("steam:qr-primary", "steam-login"),
            htmlAttr("data-autofocus", StringAsIsCodec) := "true",
            child.text <-- complete.signal.combineWith(busy.signal).map((done, loading) => if done then "查看游戏库" else if loading then "正在获取二维码…" else "刷新二维码"),
            onClick --> (_ => if complete.now() then context.navigation.go(home) else start())),
          button(cls := "tv-button glass", context.focus.target("steam:qr-back", "steam-login"), "返回", onClick --> (_ => context.navigation.back()))),
        p(cls := "yt-device-hint", "方向键 / 左摇杆选择 · A 确认 · B 返回")))
