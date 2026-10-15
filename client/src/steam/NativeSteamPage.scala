package htpc.client.steam

import com.raquo.laminar.api.L.*
import com.raquo.laminar.codecs.StringAsIsCodec
import htpc.client.tv.*
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

object NativeSteamPage:
  def login(native: NativeSteam, context: PageContext): HtmlElement = div(cls := "yt-device-page steam-login",
    onMountCallback(_ => native.openLogin()), onUnmountCallback(_ => native.closeLogin()),
    div(cls := "yt-page-heading", div(span(cls := "tv-eyebrow", "PLAY ON THIS HTPC"), h1("连接本机 Steam"),
      p("这是这台 HTPC 的 Steam 客户端登录。已有游戏库授权的账号，也需要在此扫码一次。")),
      button(cls := "tv-button glass", context.focus.target("steam:local-back", "local-login"), "返回", onClick --> (_ => context.navigation.back()))),
    div(cls := "yt-device-content",
      child <-- native.qrImage.signal.distinct.map {
        case Some(image) => div(cls := "yt-device-pairing",
          div(cls := "yt-device-qr", img(src := image, alt := "本机 Steam 登录二维码")),
          div(cls := "yt-device-steps", h2("1 · 打开 Steam 手机应用"), p("进入 Steam 令牌页面，扫描左侧二维码。"),
            h2("2 · 确认登录这台 HTPC"), p("这是本机游戏客户端的登录；完成后可继续安装游戏。")))
        case None => div(cls := "yt-device-placeholder", Icon("gamepad"))
      },
      p(cls := "yt-device-status", child.text <-- native.state.signal.combineWith(native.problem.signal, native.qrState.signal).map((s, error, qr) =>
        s.filter(_.loggedIn).map(v => s"本机已登录：${v.accountName}").getOrElse(error.getOrElse(qr match
          case "ready" => "请用 Steam 手机应用扫码并确认登录"
          case "expired" => "二维码已过期，请选择「刷新二维码」。"
          case "unavailable" => "无法读取客户端二维码，请刷新重试。"
          case _ => "正在获取本机登录二维码…")))),
      div(cls := "yt-device-actions",
        button(cls := "tv-button primary", context.focus.target("steam:local-qr-refresh", "local-login"),
          htmlAttr("data-autofocus", StringAsIsCodec) := "true",
          disabled <-- native.state.signal.map(_.exists(_.loggedIn)),
          "刷新二维码", onClick --> (_ => native.refreshQr())),
        button(cls := "tv-button primary", context.focus.target("steam:local-start", "local-login"),
          disabled <-- native.busy.signal,
          "启动 / 重连客户端", onClick --> (_ => native.start())),
        button(cls := "tv-button glass", context.focus.target("steam:local-done", "local-login"), "返回游戏库", onClick --> (_ => context.navigation.back())))))

  def controls(app: Steam, id: Int, context: PageContext): HtmlElement =
    val native = app.native
    val busy = Var(false)
    val error = Var("")
    val selectedFolder = Var(Option.empty[Int])
    val confirmRemoval = Var(false)
    val agreements = Var(Vector.empty[LocalEula])
    val agreementPage = Var(0)
    val agreementPages = agreements.signal.map(_.flatMap(e => e.text.grouped(1800).map(text => (e.title, text))))
    val current = native.state.signal.map(_.flatMap(_.games.find(_.appId == id)))
    val installation = native.state.signal.map(_.flatMap(_.install).filter(_.appIds.contains(id)))
    val signedIn = native.state.signal.map(_.exists(_.loggedIn))
    val expected = () => app.service.account.now().map(_.account_name).getOrElse("")
    def execute(action: => Future[Unit]): Unit = if !busy.now() then
      context.focus.remember()
      busy.set(true); error.set("")
      action.onComplete { result => busy.set(false); result.failed.foreach(e => error.set(e.getMessage)) }
    def act(op: String): Unit = execute(native.perform(op, id, selectedFolder.now().getOrElse(
      native.state.now().flatMap(_.folders.find(_.mounted)).map(_.index).getOrElse(-1)), expected()))
    def loadAgreements(): Unit = execute(native.loadEulas(id).map { values => agreements.set(values); agreementPage.set(0) })
    def show(condition: Signal[Boolean]): Modifier[HtmlElement] = display <-- condition.map(if _ then "" else "none")
    def buttonFor(key: String, label: String, visible: Signal[Boolean], op: String, primary: Boolean = false) =
      button(cls := (if primary then "tv-button primary" else "tv-button glass"), context.focus.target(s"steam:local-$key", "steam-local-controls"),
        show(visible), disabled <-- busy.signal, label, onClick --> (_ => act(op)))
    div(cls := "steam-local-controls", onMountCallback(_ => native.watch(id)),
      installation.map(_.exists(_.stage == 8)).distinct --> { active =>
        if active then loadAgreements() else agreements.set(Vector.empty)
      },
      p(cls := "steam-local-state", child.text <-- native.state.signal.combineWith(native.problem.signal).map((s, problem) =>
        s match
          case None => problem.getOrElse("正在连接本机客户端…")
          case Some(v) if !v.loggedIn => "本机游戏客户端尚未登录"
          case Some(v) if expected().nonEmpty && !v.accountName.equalsIgnoreCase(expected()) => "本机账号与当前游戏库不同，请连接同一账号"
          case Some(v) => v.games.find(_.appId == id).fold("正在读取安装状态…") { g =>
            if g.error.nonEmpty then s"Steam：${g.error}"
            else if g.running then "游戏运行中"
            else if g.task.nonEmpty then NativeSteam.task(g)
            else if g.downloading then s"正在下载 · ${NativeSteam.size(g.rate)}/s"
            else if g.paused then "下载已暂停"
            else if g.queued then "已加入下载队列"
            else if g.installed then "已安装 · 随时开始"
            else "尚未安装在这台 HTPC"
          })),
      div(cls := "steam-download", show(current.map(_.exists(g => g.downloading || g.paused || g.queued))),
        progressTag(maxAttr := "100", htmlAttr("value", StringAsIsCodec) <-- current.map(_.fold(0d)(_.percent.max(0).min(100)).toString)),
        span(child.text <-- current.map(_.fold("")(g => s"${NativeSteam.size(g.bytesDone)} / ${NativeSteam.size(g.bytesTotal)}")))),
      div(cls := "steam-install-config", show(installation.map(_.exists(_.stage == 7))),
        p(child.text <-- installation.map(_.fold("")(i => s"安装需要 ${NativeSteam.size(i.required)}，请选择游戏磁盘"))),
        children <-- native.state.signal.map(_.toVector.flatMap(_.folders.filter(_.mounted).map(f => (f.index, f.path)))).distinct.map(_.map { (index, path) =>
          button(cls := "tv-button subtle", context.focus.target(s"steam:folder:$index", "steam-folders"),
            child.text <-- selectedFolder.signal.map(s => (if s.contains(index) then "✓ " else "") + path),
            onClick --> (_ => selectedFolder.set(Some(index))))
        })),
      div(cls := "steam-eulas", show(installation.map(_.exists(_.stage == 8))),
        child <-- agreementPages.combineWith(agreementPage.signal).map { (pages, index) =>
          pages.lift(index).fold[HtmlElement](p("正在读取游戏许可协议…")) { (title, text) =>
            div(h3(title), pre(cls := "whitespace-pre-wrap text-sm leading-relaxed", text), p(s"${index + 1} / ${pages.size}"))
          }
        },
        div(cls := "steam-actions",
          button(cls := "tv-button glass", context.focus.target("steam:eula-prev", "steam-eulas"), "上一页",
            disabled <-- agreementPage.signal.map(_ <= 0), onClick --> (_ => agreementPage.update(_ - 1))),
          button(cls := "tv-button glass", context.focus.target("steam:eula-next", "steam-eulas"), "下一页",
            disabled <-- agreementPages.combineWith(agreementPage.signal).map((pages, index) => index + 1 >= pages.size),
            onClick --> (_ => agreementPage.update(_ + 1))),
          button(cls := "tv-button subtle", context.focus.target("steam:eula-retry", "steam-eulas"), "重新读取",
            disabled <-- busy.signal, onClick --> (_ => loadAgreements())),
          button(cls := "tv-button primary", context.focus.target("steam:eula-accept", "steam-eulas"), "同意协议并继续安装",
            disabled <-- busy.signal.combineWith(agreements.signal).map((b, es) => b || es.isEmpty),
            onClick --> (_ => execute(native.acceptEulas(id, agreements.now(), expected())))))),
      div(cls := "steam-actions",
        button(cls := "tv-button primary", context.focus.target("steam:local-login", "steam-local-controls"), show(signedIn.map(!_)),
          "连接本机 Steam", onClick --> (_ => context.navigation.go(app.localDestination))),
        buttonFor("prepare", "安装游戏", signedIn.combineWith(current, installation).map { (signed, g, i) => signed && i.isEmpty && g.exists(v => !v.installed && !v.downloading && !v.paused && !v.queued) }, "prepare", true),
        buttonFor("install", "开始下载", installation.map(_.exists(_.stage == 7)), "install", true),
        buttonFor("cancel", "取消安装", installation.map(_.exists(i => i.stage > 0 && i.stage != 14)), "cancel"),
        buttonFor("pause", "暂停下载", current.map(_.exists(g => g.downloading || g.queued && !g.paused)), "pause"),
        buttonFor("resume", "继续下载", current.map(_.exists(_.paused)), "resume", true),
        buttonFor("play", "开始游戏", current.map(_.exists(g => g.installed && !g.running && !g.downloading && !g.queued && !g.paused && g.task.isEmpty)), "play", true),
        buttonFor("cancel-launch", "取消启动", current.map(_.exists(g => g.task.nonEmpty && !g.running)), "cancel-launch"),
        button(cls := "tv-button primary", context.focus.target("steam:local-return", "steam-local-controls"), show(current.map(_.exists(_.running))),
          "返回游戏", onClick --> (_ => execute(native.returnToGame(id)))),
        buttonFor("stop", "结束游戏", current.map(_.exists(_.running)), "stop"),
        button(cls := "tv-button subtle", context.focus.target("steam:local-uninstall", "steam-local-controls"),
          show(current.map(_.exists(g => g.installed && !g.running && !g.downloading && g.task.isEmpty))), disabled <-- busy.signal,
          child.text <-- confirmRemoval.signal.map(if _ then "确认卸载游戏文件" else "卸载"),
          onClick --> { _ => if confirmRemoval.now() then { confirmRemoval.set(false); act("uninstall") } else confirmRemoval.set(true) })),
      p(cls := "steam-message", child.text <-- error.signal),
      p(cls := "steam-message", child.text <-- native.displayProblem.signal.map(_.getOrElse(""))),
      GameDisplayPanel.render(native.displayProfiles, s"steam-$id", context),
      p(cls := "steam-message", child.text <-- installation.map(_.fold("")(i =>
        if i.error.nonEmpty then i.error else if i.stage == 15 then "安装失败，请取消后重试。" else ""))))
