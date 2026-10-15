package htpc.client.youtube

import com.raquo.laminar.api.L.*
import htpc.client.tv.*
import htpc.protocol.*
import htpc.client.accounts.*
import org.scalajs.dom
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Success, Failure}

object DeviceLogin:
  def render(service: YouTubeService, context: PageContext, home: Destination): HtmlElement =
    val code = Var(Option.empty[DeviceAuthorization])
    val status = Var("正在检查设备授权配置…")
    val busy = Var(false)
    val complete = Var(false)
    val configured = Var(false)
    val seconds = Var(0)
    var disposed = false
    var timer = 0
    var clock = 0
    var generation = 0
    def cancel(): Unit =
      generation += 1
      dom.window.clearTimeout(timer)
      code.now().foreach(c => service.request(AuthOp.Cancel(c.id)))
      code.set(None)
    def schedule(delay: Int, run: Int): Unit =
      timer = dom.window.setTimeout(() => poll(run), delay)
    def poll(run: Int): Unit = if !disposed && generation == run then code.now().foreach { value =>
      if scala.scalajs.js.Date.now() >= value.expiresAt.toDouble then
        status.set("设备码已过期，请按 A 生成新设备码。")
        code.set(None)
      else service.request(AuthOp.Poll(value.id)).onComplete { result => if !disposed && generation == run then result match
        case Success(AuthorizationProgress.Pending(interval)) => schedule(interval, run)
        case Success(AuthorizationProgress.QrPending(_, _)) => status.set("授权类型不匹配，请重新生成设备码。")
        case Success(AuthorizationProgress.Authorized) =>
          code.set(None); complete.set(true); status.set("连接成功，账号已保存在此 HTPC。")
          service.refreshAccount()
          context.focus.restore(context.navigation.key(context.navigation.current.now()))
        case Success(AuthorizationProgress.Expired) => code.set(None); status.set("设备码已过期或取消，请重新生成。")
        case Success(AuthorizationProgress.Denied(message)) => code.set(None); status.set(message)
        case Failure(error) => status.set(s"连接暂时中断，正在重试：${error.getMessage}"); schedule(math.max(10000, value.intervalMs), run)
      }
    }
    def start(): Unit = if !busy.now() then
      cancel(); val run = generation
      busy.set(true); complete.set(false); status.set("正在向 Google 获取设备码…")
      service.request(AuthOp.Start(Providers.YouTube)).onComplete {
        case Success(value) if disposed || generation != run => service.request(AuthOp.Cancel(value.id))
        case Success(value) =>
          busy.set(false); code.set(Some(value)); seconds.set(math.max(0, ((value.expiresAt - scala.scalajs.js.Date.now().toLong) / 1000).toInt))
          status.set("等待手机授权… 授权后此页面会自动更新。")
          schedule(value.intervalMs, run)
        case Failure(error) => if !disposed && generation == run then { busy.set(false); status.set(error.getMessage) }
      }
    div(cls := "yt-device-page",
      onMountCallback { _ =>
        clock = dom.window.setInterval(() => code.now().foreach(v => seconds.set(math.max(0, ((v.expiresAt - scala.scalajs.js.Date.now().toLong) / 1000).toInt))), 1000)
        service.request(AuthOp.Status(Providers.YouTube)).onComplete {
          case Success(value) if !disposed =>
            configured.set(value.configured)
            if value.authorized then { complete.set(true); status.set("此 HTPC 已连接你的 YouTube 账号。") }
            else if value.configured then start()
            else status.set("设备登录尚未配置。应用维护者配置一次后，用户即可用手机授权。")
          case Failure(error) if !disposed => status.set(error.getMessage)
          case _ => ()
        }
      },
      onUnmountCallback(_ => { disposed = true; cancel(); dom.window.clearInterval(clock) }),
      div(cls := "yt-page-heading", div(span(cls := "tv-eyebrow", "CONNECT ON YOUR PHONE"), h1("用手机连接 YouTube"),
        p("在手机上登录并授权，手柄留在手边就好。"))),
      div(cls := "yt-device-content",
        child <-- code.signal.map {
          case Some(value) => div(cls := "yt-device-pairing",
            div(cls := "yt-device-qr", img(src := value.qrImage, alt := "Google 设备授权网址二维码")),
            div(cls := "yt-device-steps", h2("1. 用手机扫描二维码"), p("或在手机浏览器中打开："), strong(cls := "yt-device-url", value.verificationUrl),
              h2("2. 输入设备码并授权"), span(cls := "yt-device-code", value.userCode),
              p("设备码区分大小写，请按原样输入。"), span(cls := "yt-device-expiry", child.text <-- seconds.signal.map(s => f"有效时间 ${s / 60}%02d:${s % 60}%02d"))))
          case None => div(cls := "yt-device-placeholder", child.text <-- complete.signal.map(done => if done then "✓" else "◎"))
        },
        p(cls := "yt-device-status", child.text <-- status.signal),
        div(cls := "yt-device-actions",
          button(cls := "tv-button primary", context.focus.target("login:primary", "device-actions"),
            child.text <-- complete.signal.map(done => if done then "返回 YouTube" else "生成新设备码"),
            disabled <-- busy.signal.combineWith(configured.signal, complete.signal).map((b, c, done) => b || (!c && !done)),
            onClick --> (_ => if complete.now() then context.navigation.go(home) else start())),
          button(cls := "tv-button glass", context.focus.target("login:back", "device-actions"), "返回", onClick --> (_ => context.navigation.back())),
          child <-- complete.signal.map { done => if !done then div() else button(cls := "tv-button subtle",
            context.focus.target("login:disconnect", "device-actions"), "断开此 HTPC", disabled <-- busy.signal,
            onClick --> { _ => busy.set(true); service.signOut().onComplete { result => if !disposed then
              busy.set(false)
              result match
                case Success(_) => complete.set(false); status.set("已断开此 HTPC 的 YouTube 账号。")
                case Failure(error) => status.set(error.getMessage)
            } }) }),
        child <-- configured.signal.map { enabled => if enabled then div() else div(cls := "yt-device-setup",
          h2("应用维护者设置"), p("在 Google Cloud 启用 YouTube Data API v3；创建 TVs and Limited Input devices 类型的 OAuth 客户端。"),
          p("将 Google TV 客户端 JSON 写入 serverStorage 的 youtube/client。配置后返回本页即可，无需重启服务。")) },
        p(cls := "yt-device-hint", "方向键 / 左摇杆选择 · A 确认 · B 返回")
      )
    )
