package htpc.server

import cats.effect.IO
import htpc.protocol.*
import java.nio.file.Files

/** Generic launch planning; application authentication and process ownership stay with Apps. */
final class GameDisplayService(config: Config, display: Sway):
  def support: GameDisplaySupport =
    val ready = config.gamescope.exists(p => Files.isExecutable(java.nio.file.Path.of(p))) && config.gamescopeLauncher.exists(Files.isRegularFile(_))
    GameDisplaySupport(Capability(ready, if ready then config.gamescope.get else "请安装 gamescope 并部署栖间启动器"),
      Capability(config.mangoapp.exists(p => Files.isExecutable(java.nio.file.Path.of(p))), "性能显示需要 mangoapp"))

  def prepare(application: String, settings: GameDisplaySettings): IO[Either[SystemError, GameDisplayLaunch]] = (for
    _ <- Fault.require(GameDisplay.validApplication(application), "Invalid application key")
    state <- display.handle(SwayOp.Snapshot).flatMap(r => IO.fromEither(r.left.map(e => Fault(e.code, e.message))))
    focusedOutput = state.workspaces.find(_.focused).map(_.output)
    output <- IO.fromOption(state.outputs.filter(_.active).sortBy(o => if focusedOutput.contains(o.name) then 0 else 1)
      .flatMap(_.currentMode).headOption)(Fault(ErrorCode.Unavailable, "没有可用的电视输出"))
    args <- if settings.enabled then IO.fromEither(GameDisplay.arguments(settings, output).left.map(Fault(ErrorCode.InvalidArgument, _)))
      else IO.pure(Vector.empty[String])
    _ <- IO.raiseWhen(settings.enabled && !support.gamescope.available)(Fault(ErrorCode.Unavailable, support.gamescope.detail))
    _ <- IO.raiseWhen(settings.enabled && settings.hud && !support.mangoapp.available)(Fault(ErrorCode.Unavailable, support.mangoapp.detail))
    prefix = if !settings.enabled then Vector.empty else Vector("/usr/bin/env", s"HTPC_GAMESCOPE=${config.gamescope.get}") ++
      (if settings.hud then Vector("MANGOHUD_CONFIG=fps,frametime,frame_timing,fsr,position=top-left,font_size=24") else Vector.empty) ++
      Vector("/bin/sh", config.gamescopeLauncher.get.toString, application) ++ args
  yield GameDisplayLaunch(application, prefix, output)).attempt.map(_.left.map { e =>
    val f = Fault.from(e); SystemError(f.code, f.getMessage)
  })
