package htpc.client.tv

import com.raquo.laminar.api.L.*
import htpc.client.infra.GameDisplayProfiles
import htpc.protocol.*
import scala.concurrent.ExecutionContext.Implicits.global

/** Controller-first, application-neutral settings. Every option is an ordinary focusable button. */
object GameDisplayPanel:
  def render(profiles: GameDisplayProfiles, application: String, context: PageContext): HtmlElement =
    val expanded = Var(false)
    val settings = Var(GameDisplaySettings())
    val saved = Var(GameDisplaySettings())
    val ready = Var(false)
    val busy = Var(false)
    val message = Var("")
    val capability = Var(Option.empty[GameDisplaySupport])
    var revision = ""
    var mounted = false
    def restoreFocus(): Unit = context.focus.restore(context.navigation.key(context.navigation.current.now()))
    def load(restore: Boolean = false): Unit =
      if restore then context.focus.remember()
      ready.set(false); busy.set(true)
      profiles.load(application).zip(profiles.support()).onComplete { result =>
        if mounted then
          busy.set(false)
          result.fold(e => message.set(e.getMessage), { case ((s, r), support) =>
            settings.set(s); saved.set(s); revision = r; capability.set(Some(support)); ready.set(true)
          })
          if restore then restoreFocus()
      }
    def option(key: String, text: Signal[String])(change: GameDisplaySettings => GameDisplaySettings) =
      button(cls := "tv-button glass", context.focus.target(s"display:$application:$key", s"game-display:$application:$key"),
        disabled <-- ready.signal.combineWith(busy.signal).map((r, b) => !r || b), child.text <-- text,
        onClick --> { _ => settings.update(change); message.set("") })
    def cycle[A](value: A, values: Vector[A]): A = values((values.indexOf(value) + 1) % values.size)
    def resolution(s: GameDisplaySettings): String = if s.width == 0 then "电视原生" else s"${s.width} × ${s.height}"
    def scaling(s: GameDisplaySettings): String = s.scaling match
      case GameScaling.Linear => "默认缩放"
      case GameScaling.Fsr => "FSR 1 放大"
      case GameScaling.Integer => "整数缩放"
    div(cls := "game-display-panel", onMountCallback(_ => { mounted = true; load() }), onUnmountCallback(_ => mounted = false),
      button(cls := "tv-button glass", context.focus.target(s"display:$application:open", s"game-display:$application:open"),
        child.text <-- saved.signal.combineWith(expanded.signal).map((s, open) =>
          (if open then "收起画面设置" else "画面设置") + (if s.enabled then s" · ${resolution(s)} · ${if s.fps == 0 then "不限帧" else s"${s.fps} FPS"}" else " · 原生运行")),
        onClick --> (_ => expanded.update(!_))),
      div(cls := "game-display-options", display <-- expanded.signal.map(if _ then "" else "none"),
        h3("游戏画面 · Gamescope"), p("按 A 切换选项，保存后下次从栖间启动生效。输出分辨率自动跟随电视；游戏内请同步选择渲染分辨率和全屏模式。"),
        p(child.text <-- capability.signal.map(_.fold("")(c => if c.gamescope.available then "FSR 1 是空间放大；较低渲染分辨率可减轻 GPU 负载。" else c.gamescope.detail))),
        div(cls := "game-display-grid",
          option("enabled", settings.signal.map(s => s"Gamescope：${if s.enabled then "开启" else "关闭"}"))(s => s.copy(enabled = !s.enabled)),
          option("resolution", settings.signal.map(s => s"渲染分辨率：${resolution(s)}")) { s =>
            val (w, h) = cycle((s.width, s.height), Vector((0, 0), (1280, 720), (1600, 900), (1920, 1080), (2560, 1440), (3840, 2160)))
            s.copy(width = w, height = h)
          },
          option("fps", settings.signal.map(s => s"帧率上限：${if s.fps == 0 then "不限制" else s"${s.fps} FPS"}"))(s => s.copy(fps = cycle(s.fps, Vector(60, 30, 0, 90, 120)))),
          option("scaling", settings.signal.map(s => s"缩放方式：${scaling(s)}"))(s => s.copy(scaling = cycle(s.scaling, GameScaling.values.toVector))),
          option("sharpness", settings.signal.map(s => s"FSR 锐度：${s.sharpness}（0 最锐 / 20 最柔）"))(s => s.copy(sharpness = cycle(s.sharpness, Vector(0, 5, 10, 15, 20)))),
          option("hud", settings.signal.map(s => s"帧率 / 帧时间显示：${if s.hud then "开启" else "关闭"}"))(s => s.copy(hud = !s.hud))),
        div(cls := "steam-actions",
          button(cls := "tv-button primary", context.focus.target(s"display:$application:save", s"game-display:$application:actions"),
            disabled <-- ready.signal.combineWith(busy.signal).map((r, b) => !r || b), "保存画面设置", onClick --> { _ =>
              context.focus.remember()
              val value = settings.now(); busy.set(true); message.set("")
              profiles.prepare(application, value).flatMap(_ => profiles.save(application, value, revision)).onComplete { result =>
                if mounted then
                  busy.set(false)
                  result.fold(e => message.set(s"保存失败：${e.getMessage}；可重新加载后重试。"), stamp => {
                    revision = stamp.revision; saved.set(value); message.set("已保存，下次启动生效。")
                  })
                  restoreFocus()
              }
            }),
          button(cls := "tv-button subtle", context.focus.target(s"display:$application:reload", s"game-display:$application:actions"),
            disabled <-- busy.signal, "重新加载", onClick --> (_ => { message.set(""); load(true) }))),
        p(cls := "steam-message", child.text <-- message.signal)))
