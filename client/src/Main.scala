package htpc.client

import com.raquo.laminar.api.L.*
import htpc.protocol.*
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import org.scalajs.dom
import scala.concurrent.ExecutionContext.Implicits.global

object Main:
  private val api = new ApiClient
  private val page = Var("chromium")
  private val capabilities = Var(Option.empty[Capabilities])
  private val browsers = Var(Vector.empty[ChromiumInstance])
  private val wines = Var(Vector.empty[WineInstance])
  private val selectedBrowser = Var(Option.empty[ChromiumId])
  private val selectedWine = Var(Option.empty[WineId])
  private val tabs = Var(Vector.empty[TabInfo])
  private val selectedTab = Var(Option.empty[TabRef])
  private val display = Var(Option.empty[DisplaySnapshot])
  private val busy = Var(false)
  private val message = Var("就绪")
  private val error = Var(false)
  private val result = Var("执行结果会显示在这里。")
  private val screenshot = Var(Option.empty[Screenshot])
  private val cookies = Var("[]")
  private val browserName = Var("登录会话")
  private val browserProfile = Var("")
  private val proxy = Var("")
  private val address = Var("https://example.com")
  private val script = Var("({ title: document.title, url: location.href })")
  private val wineName = Var("DLL 签名服务")
  private val wineProfile = Var("")
  private val dll = Var("")
  private val function = Var("echo_json")
  private val freeFunction = Var("free_json")
  private val arguments = Var("{\n  \"message\": \"Hello from Scala.js\"\n}")
  private var refreshTimer = 0

  private def failure[E](e: CallError[E]): String = e match
    case CallError.Domain(d: DomainError) => s"${d.code}: ${d.message}"
    case CallError.Domain(d) => d.toString
    case CallError.Transport(m) => s"连接失败：$m"
    case CallError.Protocol(m) => s"协议校验失败：$m"

  private def notifyError(text: String): Unit = { error.set(true); message.set(text) }
  private def act[E, A](label: String, request: Request[E, A])(done: A => Unit): Unit =
    if !busy.now() then
      busy.set(true); error.set(false); message.set(s"$label…")
      api.call(request).foreach { response =>
        busy.set(false)
        response match
          case Left(e) => notifyError(failure(e))
          case Right(value) => message.set(s"${label}完成"); done(value)
      }

  private def refreshTabs(id: ChromiumId): Unit = api.call(ChromiumOp.ListTab(id)).foreach {
    case Right(values) if selectedBrowser.now().contains(id) =>
      tabs.set(values)
      if !selectedTab.now().exists(t => values.exists(_.ref == t)) then selectTab(values.headOption.map(_.ref))
    case _ => ()
  }

  private def selectTab(tab: Option[TabRef]): Unit =
    selectedTab.set(tab)
    screenshot.set(None)
    tab.flatMap(t => tabs.now().find(_.ref == t)).foreach(t => address.set(t.url))

  private def refresh(): Unit =
    api.call(SystemOp.GetCapabilities).foreach {
      case Right(c) => capabilities.set(Some(c)); if c.sway.available then refreshDisplay()
      case Left(e) => notifyError(failure(e))
    }
    api.call(ChromiumOp.ListInstance).foreach {
      case Right(values) =>
        browsers.set(values)
        if !selectedBrowser.now().exists(id => values.exists(_.id == id)) then
          selectedBrowser.set(values.headOption.map(_.id)); selectedTab.set(None); tabs.set(Vector.empty)
        selectedBrowser.now().foreach(refreshTabs)
      case Left(e) => notifyError(failure(e))
    }
    api.call(WineOp.ListInstance).foreach {
      case Right(values) =>
        wines.set(values)
        if !selectedWine.now().exists(id => values.exists(_.id == id)) then selectedWine.set(values.headOption.map(_.id))
      case Left(e) => notifyError(failure(e))
    }

  private def scheduleRefresh(): Unit =
    dom.window.clearTimeout(refreshTimer)
    refreshTimer = dom.window.setTimeout(() => refresh(), 200)

  private def refreshDisplay(): Unit = api.call(SwayOp.Snapshot).foreach {
    case Right(value) => display.set(Some(value))
    case Left(_) => display.set(None)
  }

  private def optional(value: String) = Option(value.trim).filter(_.nonEmpty)
  private def field(labelText: String, state: Var[String], hint: String = ""): HtmlElement = label(
    cls := "field", span(labelText), input(value <-- state.signal, placeholder := hint, onInput.mapToValue --> state)
  )
  private def action(labelText: String, primary: Boolean = false)(handler: => Unit): HtmlElement = button(
    cls := (if primary then "button primary" else "button"), labelText,
    disabled <-- busy.signal, onClick --> (_ => handler)
  )
  private def panel(titleText: String, subtitle: String)(content: Modifier[HtmlElement]*): HtmlElement = sectionTag(
    cls := "panel", div(cls := "panel-heading", h2(titleText), p(subtitle)), content
  )
  private def empty(titleText: String, text: String): HtmlElement = div(cls := "empty", div(cls := "empty-mark", "○"), h3(titleText), p(text))

  private def overview: HtmlElement = div(cls := "stats",
    div(cls := "stat", span("HEADLESS CHROMIUM"), strong(child.text <-- browsers.signal.map(_.size.toString)), small("独立登录环境")),
    div(cls := "stat", span("WINE INSTANCES"), strong(child.text <-- wines.signal.map(_.size.toString)), small("Windows DLL 工作实例")),
    div(cls := "stat", span("DISPLAY"), strong(child.text <-- display.signal.map(_.fold("—")(_.windows.size.toString))), small("Sway 应用窗口"))
  )

  private def browserPage: HtmlElement = div(cls := "workspace",
    div(cls := "resource-column",
      panel("浏览器实例", "每个实例拥有独立 Cookie 与 profile")(
        div(cls := "instance-list", children <-- browsers.signal.map(_.map { b =>
          button(cls <-- selectedBrowser.signal.map(id => "instance " + (if id.contains(b.id) then "selected" else "")),
            span(cls := "instance-icon", "C"), span(cls := "instance-meta", strong(b.name), small(b.profile.getOrElse("临时 profile"))),
            span(cls := (if b.state == InstanceState.Running then "dot" else "dot failed")),
            onClick --> (_ => { selectedBrowser.set(Some(b.id)); selectedTab.set(None); tabs.set(Vector.empty); refreshTabs(b.id) }))
        })),
        child <-- browsers.signal.map(v => if v.isEmpty then empty("暂无实例", "创建一个 headless 登录环境") else div())
      ),
      panel("新建实例", "填写 profile 名称可跨实例保留登录状态")(
        field("名称", browserName), field("Profile（选填）", browserProfile, "例如：music-account"),
        field("代理（选填）", proxy, "http://127.0.0.1:7890"),
        action("＋ 创建 Chromium", true) {
          act("创建浏览器", ChromiumOp.NewInstance(ChromiumOptions(browserName.now(), optional(browserProfile.now()), optional(proxy.now())))) { b =>
            selectedBrowser.set(Some(b.id)); refresh()
          }
        }
      )
    ),
    div(cls := "main-column", child <-- selectedBrowser.signal.distinct.map {
      case None => panel("浏览器工作区", "登录 · 导航 · JavaScript · Cookie")(
        empty("选择或创建浏览器实例", "页面操作通过 CDP 完成，无需可见窗口。"))
      case Some(id) => div(
        panel("标签页", id.value)(
          div(cls := "toolbar",
            action("＋ 新标签页") { act("创建标签页", ChromiumOp.NewTab(id)) { t => selectTab(Some(t.ref)); refreshTabs(id) } },
            action("删除实例") { act("删除实例", ChromiumOp.DeleteInstance(id))(_ => refresh()) }
          ),
          div(cls := "tabs", children <-- tabs.signal.map(_.map { t =>
            button(cls <-- selectedTab.signal.map(v => "tab " + (if v.contains(t.ref) then "active" else "")),
              t.title match { case "" => t.url; case title => title }, onClick --> (_ => selectTab(Some(t.ref))))
          })),
          child <-- selectedTab.signal.distinct.map {
            case None => empty("没有标签页", "点击新标签页开始。")
            case Some(tab) => tabPanel(tab)
          }
        ),
        panel("Cookie", "实例级存储，包括 HttpOnly Cookie")(
          div(cls := "toolbar",
            action("读取 Cookie") { act("读取 Cookie", ChromiumOp.GetCookies(id))(cs => cookies.set(cs.asJson.spaces2)) },
            action("写入 Cookie") {
              parse(cookies.now()).flatMap(_.as[Vector[Cookie]]) match
                case Left(e) => notifyError(e.getMessage)
                case Right(cs) => act("写入 Cookie", ChromiumOp.SetCookies(id, cs))(_ => ())
            }
          ),
          textArea(cls := "code", rows := 8, value <-- cookies.signal, onInput.mapToValue --> cookies),
          p(cls := "muted", "写入采用完整 Cookie 结构。删除单个 Cookie 可调用 cookie.delete API。")
        )
      )
    })
  )

  private def tabPanel(tab: TabRef): HtmlElement = div(
    div(cls := "navigation",
      action("←") { act("后退", ChromiumOp.Back(tab))(n => { address.set(n.url); refreshTabs(tab.instance) }) },
      action("→") { act("前进", ChromiumOp.Forward(tab))(n => { address.set(n.url); refreshTabs(tab.instance) }) },
      action("↻") { act("刷新", ChromiumOp.Reload(tab))(n => { address.set(n.url); refreshTabs(tab.instance) }) },
      input(cls := "address", value <-- address.signal, onInput.mapToValue --> address, placeholder := "https://…"),
      action("打开", true) { act("导航", ChromiumOp.Goto(tab, Navigation(address.now())))(n => { address.set(n.url); refreshTabs(tab.instance) }) }
    ),
    div(cls := "toolbar",
      action("截取页面") { act("截图", ChromiumOp.TakeScreenshot(tab))(s => screenshot.set(Some(s))) },
      action("关闭标签页") { act("关闭标签页", ChromiumOp.DeleteTab(tab))(_ => refreshTabs(tab.instance)) }
    ),
    child <-- screenshot.signal.map {
      case Some(s) => div(cls := "screenshot", img(src := s"data:${s.mimeType};base64,${s.base64}", alt := "Headless 页面截图"))
      case None => div()
    },
    div(cls := "subheading", h3("JavaScript"), span("await Promise · JSON 返回值")),
    textArea(cls := "code", rows := 7, value <-- script.signal, onInput.mapToValue --> script),
    div(cls := "toolbar", action("执行 JavaScript", true) {
      act("执行 JavaScript", ChromiumOp.EvalJS(tab, script.now(), EvalOptions())) {
        case JsValue.Value(value) => result.set(value.spaces2)
        case JsValue.Undefined => result.set("undefined")
      }
    }),
    pre(cls := "result", child.text <-- result.signal)
  )

  private def winePage: HtmlElement = div(cls := "workspace",
    div(cls := "resource-column",
      panel("Wine 实例", "独立 prefix · 常驻 DLL host · 串行调用")(
        div(cls := "instance-list", children <-- wines.signal.map(_.map { w =>
          button(cls <-- selectedWine.signal.map(id => "instance " + (if id.contains(w.id) then "selected" else "")),
            span(cls := "instance-icon wine", "W"), span(cls := "instance-meta", strong(w.name), small(w.profile.getOrElse("临时 prefix"))),
            span(cls := (if w.state == InstanceState.Running then "dot" else "dot failed")), onClick --> (_ => selectedWine.set(Some(w.id))))
        }))
      ),
      panel("新建实例", "宿主 ABI：json-utf8-v1 / Windows x64")(
        field("名称", wineName), field("Profile（选填）", wineProfile),
        action("＋ 创建 Wine 实例", true) {
          act("创建 Wine 实例", WineOp.NewInstance(WineOptions(wineName.now(), optional(wineProfile.now())))) { w => selectedWine.set(Some(w.id)); refresh() }
        }
      ),
      child <-- capabilities.signal.map(c => p(cls := "capability-note", c.fold("读取能力信息…")(_.wine.detail)))
    ),
    div(cls := "main-column", child <-- selectedWine.signal.distinct.map {
      case None => panel("DLL 执行", "前端构造输入，Wine 宿主处理原生调用")(
        empty("选择或创建 Wine 实例", "DLL 适配器接收 UTF-8 JSON，并返回 UTF-8 JSON。"))
      case Some(id) => panel("执行 DLL 函数", id.value)(
        field("DLL 绝对路径", dll, "/path/to/adapter.dll"),
        div(cls := "form-row", field("导出函数", function), field("释放函数（选填）", freeFunction)),
        label(cls := "field", span("JSON 参数"), textArea(cls := "code", rows := 10, value <-- arguments.signal, onInput.mapToValue --> arguments)),
        div(cls := "toolbar",
          action("执行 DLL", true) {
            parse(arguments.now()) match
              case Left(e) => notifyError(e.message)
              case Right(input) => act("执行 DLL", WineOp.Execute(id, DllInvocation(dll.now(), function.now(), input, optional(freeFunction.now())), ExecuteOptions()))(j => result.set(j.spaces2))
          },
          action("删除实例") { act("删除 Wine 实例", WineOp.DeleteInstance(id))(_ => refresh()) }
        ),
        pre(cls := "result", child.text <-- result.signal)
      )
    })
  )

  private def swayPage: HtmlElement = div(child <-- display.signal.map {
    case None => panel("Sway 显示控制", "窗口 · 工作区 · 输出")(
      empty("尚未连接合成器", capabilities.now().fold("读取能力信息…")(_.sway.detail)),
      action("刷新状态") { refreshDisplay() })
    case Some(snapshot) => div(
      panel("输出设备", s"Compositor session: ${snapshot.session}")(
        snapshot.outputs.map { output => div(cls := "output-row",
          div(strong(output.name), p(cls := "muted", output.currentMode.fold("无输出模式")(m => s"${m.width} × ${m.height} · ${m.refreshMilliHz / 1000.0} Hz"))),
          action(if output.power then "关闭输出" else "开启输出") {
            act("设置输出电源", SwayOp.SetOutputPower(output.name, !output.power))(_ => refreshDisplay())
          },
          select(onChange.mapToValue --> { index => index.toIntOption.flatMap(output.modes.lift).foreach(m =>
            act("设置输出模式", SwayOp.SetOutputMode(output.name, m))(_ => refreshDisplay())) },
            option(value := "", "选择输出模式"), output.modes.zipWithIndex.map { (m, i) => option(value := i.toString, s"${m.width}×${m.height}@${m.refreshMilliHz / 1000.0}") })
        ) }
      ),
      panel("工作区", "通过 IPC 切换可见工作区")(
        div(cls := "toolbar", snapshot.workspaces.map { ws =>
          action(ws.name, ws.focused) { act("切换工作区", SwayOp.ActivateWorkspace(ws.name))(_ => refreshDisplay()) }
        })
      ),
      panel("应用窗口", s"${snapshot.windows.size} 个窗口")(
        snapshot.windows.map { window =>
          val workspace = Var(window.workspace)
          div(cls := "window-row", div(strong(window.title), p(cls := "muted", s"${window.appId.getOrElse("unknown")} · ${window.workspace}")),
            div(cls := "toolbar",
              action("聚焦", window.focused) { act("聚焦窗口", SwayOp.Focus(window.ref))(_ => refreshDisplay()) },
              action(if window.fullscreen then "退出全屏" else "全屏") { act("设置全屏", SwayOp.SetFullscreen(window.ref, !window.fullscreen))(_ => refreshDisplay()) },
              input(value <-- workspace.signal, onInput.mapToValue --> workspace),
              action("移动") { act("移动窗口", SwayOp.Move(window.ref, workspace.now()))(_ => refreshDisplay()) },
              action("关闭") { act("关闭窗口", SwayOp.Close(window.ref))(_ => refreshDisplay()) }
            )
          )
        }
      )
    )
  })

  private def app: HtmlElement = div(cls := "app",
    onMountCallback(_ => { api.connect(); refresh() }),
    onUnmountCallback(_ => { api.disconnect(); dom.window.clearTimeout(refreshTimer) }),
    api.resync --> (_ => scheduleRefresh()),
    api.events(Topic.Chromium) --> (_ => scheduleRefresh()),
    api.events(Topic.Wine) --> (_ => scheduleRefresh()),
    api.events(Topic.Sway) --> (_ => refreshDisplay()),
    asideTag(cls := "sidebar",
      div(cls := "brand", span(cls := "brand-mark", "H"), div(strong("HTPC Next"), small("LOCAL CAPABILITY CONSOLE"))),
      div(cls := "nav-label", "基础服务"),
      Vector(("chromium", "◎", "Chromium", "登录与网页自动化"), ("wine", "◇", "Wine", "Windows DLL 调用"), ("sway", "▣", "Sway", "窗口与显示输出")).map { (id, icon, title, sub) =>
        button(cls <-- page.signal.map(p => "nav-item " + (if p == id then "active" else "")),
          span(cls := "nav-icon", icon), span(strong(title), small(sub)), onClick --> (_ => { page.set(id); result.set("执行结果会显示在这里。") }))
      },
      div(cls := "sidebar-footer", span(cls <-- api.connected.signal.map(c => if c then "dot" else "dot failed")),
        span(child.text <-- api.connected.signal.map(c => if c then "事件流已连接" else "正在连接…")), small("Scala 3 · Scala.js · Laminar"))
    ),
    mainTag(cls := "main",
      headerTag(cls := "topbar", div(span(cls := "eyebrow", "HTPC / FOUNDATION"), h1("本机能力工作台"), p("用类型化接口连接网页、原生程序与显示设备。")), action("刷新状态") { refresh() }),
      overview,
      div(cls <-- error.signal.map(e => "status " + (if e then "error" else "")), span(cls <-- busy.signal.map(b => if b then "pulse" else "status-mark")), child.text <-- message.signal),
      child <-- page.signal.map {
        case "wine" => winePage
        case "sway" => swayPage
        case _ => browserPage
      },
      footerTag("HTTP 基础服务 / 强类型协议 / 业务逻辑运行于前端")
    )
  )

  def main(args: Array[String]): Unit =
    renderOnDomContentLoaded(dom.document.getElementById("app"), app)
