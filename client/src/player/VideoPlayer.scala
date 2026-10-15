package htpc.client.player

import com.raquo.laminar.api.L.*
import com.raquo.laminar.codecs.StringAsIsCodec
import htpc.client.tv.*
import org.scalajs.dom

/** Internal full-screen page, deliberately absent from AppRegistry and home feeds. */
object VideoPlayer:
  def page(request: VideoRequest): Page = Page(context => render(context, request))

  def render(context: PageContext, request: VideoRequest): HtmlElement =
    val host = div(cls := "video-player-host")
    val playback = new Playback(request, host.ref)
    val state = playback.state.signal
    val partsOpen = Var(false)
    val controls = Var(true)
    var hideTimer = 0
    var mounted = false
    def reveal(): Unit =
      controls.set(true)
      dom.window.clearTimeout(hideTimer)
      if mounted then hideTimer = dom.window.setTimeout(() => {
        if playback.state.now().phase == PlaybackPhase.Playing && !partsOpen.now() then controls.set(false)
      }, 4500)
    def time(value: Double): String =
      val seconds = math.max(0, value.toInt)
      if seconds >= 3600 then f"${seconds / 3600}%d:${seconds / 60 % 60}%02d:${seconds % 60}%02d"
      else f"${seconds / 60}%d:${seconds % 60}%02d"
    def action(id: String, label: String, enabled: PlaybackState => Boolean = _.ready)(run: => Unit) =
      button(cls := "tv-button glass", context.focus.target(s"player:$id", "player-controls"),
        disabled <-- state.map(s => !enabled(s)), label, onClick --> (_ => { reveal(); run }))
    val activity: scala.scalajs.js.Function1[dom.Event, Unit] = _ => reveal()
    div(cls := "video-player-page",
      htmlAttr("data-tv-scope", StringAsIsCodec) := "video-player",
      htmlAttr("role", StringAsIsCodec) := "dialog",
      htmlAttr("aria-modal", StringAsIsCodec) := "true",
      htmlAttr("aria-label", StringAsIsCodec) := "全屏视频播放器",
      htmlAttr("data-player-index", StringAsIsCodec) <-- state.map(_.index.toString),
      htmlAttr("data-player-phase", StringAsIsCodec) <-- state.map(_.phase.toString),
      htmlAttr("data-controls", StringAsIsCodec) <-- controls.signal.map(_.toString),
      onMountCallback { _ =>
        mounted = true
        dom.document.addEventListener("keydown", activity)
        dom.document.addEventListener("focusin", activity)
        dom.document.addEventListener("tv-activity", activity)
        playback.mount(); reveal()
      },
      onUnmountCallback { _ =>
        mounted = false
        dom.window.clearTimeout(hideTimer)
        dom.document.removeEventListener("keydown", activity)
        dom.document.removeEventListener("focusin", activity)
        dom.document.removeEventListener("tv-activity", activity)
        playback.close()
      },
      state.map(_.phase).distinct --> { phase => if phase != PlaybackPhase.Playing then controls.set(true) else reveal() },
      onMouseMove --> (_ => reveal()),
      host,
      // This cover keeps the iframe from taking controller/keyboard focus.
      button(cls := "video-player-surface", tabIndex := -1, htmlAttr("aria-label", StringAsIsCodec) := "显示播放控制",
        onClick --> (_ => { reveal(); dom.document.querySelector("[data-tv-focus='player:toggle']").asInstanceOf[dom.HTMLElement].focus() })),
      div(cls := "video-player-heading video-player-overlay",
        button(cls := "tv-button glass", context.focus.target("player:back", "player-header"), htmlAttr("data-autofocus", StringAsIsCodec) := "true",
          "← 返回", onClick --> (_ => context.navigation.back())),
        div(h1(child.text <-- state.map(s => request.parts(s.index).title)),
          p(child.text <-- state.map(s => s"P${s.index + 1} / ${request.parts.size}")))),
      div(cls := "video-player-bottom video-player-overlay",
        div(cls := "video-player-status", htmlAttr("role", StringAsIsCodec) := "status", child.text <-- state.map { s =>
          if s.message.nonEmpty then s.message else s.phase match
            case PlaybackPhase.Loading => "正在加载…"
            case PlaybackPhase.Buffering => "正在缓冲…"
            case PlaybackPhase.Ended => if s.index == request.parts.size - 1 then "已播放完全部分 P" else "本 P 已结束"
            case PlaybackPhase.Paused => "已暂停"
            case _ => ""
        }),
        div(cls := "video-player-progress",
          progressTag(maxAttr := "1", htmlAttr("value", StringAsIsCodec) <-- state.map(s => s.duration.fold(0.0)(d => math.min(1, s.position / d)).toString)),
          span(child.text <-- state.map(s => time(s.position) + " / " + s.duration.fold("直播 / 未知时长")(time)))),
        div(cls := "video-player-controls",
          action("previous", "上一 P", _.index > 0)(playback.previous()),
          action("rewind", "−10 秒", s => s.ready && s.capabilities.seek)(playback.seekBy(-10)),
          button(cls := "tv-button primary", context.focus.target("player:toggle", "player-controls"), disabled <-- state.map(s => !s.ready),
            child.text <-- state.map(s => if s.phase == PlaybackPhase.Playing || s.phase == PlaybackPhase.Buffering then "暂停" else if s.phase == PlaybackPhase.Ended then "重播" else "播放"),
            onClick --> (_ => { reveal(); playback.toggle() })),
          action("forward", "+10 秒", s => s.ready && s.capabilities.seek)(playback.seekBy(10)),
          action("next", "下一 P", _.index < request.parts.size - 1)(playback.next()),
          action("parts", "选 P", _ => request.parts.size > 1)(partsOpen.update(!_)),
          button(cls := "tv-button glass", context.focus.target("player:auto-next", "player-controls"),
            child.text <-- state.map(s => if s.autoNext then "自动下一 P：开" else "自动下一 P：关"),
            onClick --> (_ => { reveal(); playback.setAutoNext(!playback.state.now().autoNext) })),
          button(cls := "tv-button glass", context.focus.target("player:speed", "player-controls"), disabled <-- state.map(s => !s.ready || !s.capabilities.speed),
            child.text <-- state.map(s => s"${s.speed}×"), onClick --> (_ => {
              reveal(); val rates = Vector(0.5, 1.0, 1.25, 1.5, 2.0)
              playback.setSpeed(rates((rates.indexOf(playback.state.now().speed) + 1) % rates.size))
            })),
          action("volume-down", "音量 −", s => s.ready && s.capabilities.volume)(playback.setVolume(playback.state.now().volume - 0.1)),
          action("volume-up", "音量 +", s => s.ready && s.capabilities.volume)(playback.setVolume(playback.state.now().volume + 0.1)),
          button(cls := "tv-button glass", context.focus.target("player:retry", "player-controls"),
            display <-- state.map(s => if s.phase == PlaybackPhase.Failed then "" else "none"), "重试", onClick --> (_ => playback.retry()))),
        div(cls := "video-player-parts", display <-- partsOpen.signal.map(if _ then "" else "none"),
          request.parts.zipWithIndex.map { (part, index) =>
            button(cls <-- state.map(s => if s.index == index then "tv-button primary" else "tv-button glass"),
              context.focus.target(s"player:part:$index", "player-parts"), s"P${index + 1} · ${part.title}",
              onClick --> (_ => { playback.select(index); partsOpen.set(false); reveal(); dom.document.querySelector("[data-tv-focus='player:parts']").asInstanceOf[dom.HTMLElement].focus() }))
          }),
        p(cls := "video-player-hint", "A 确认 · 方向键选择控制 · B 返回"))
    )
