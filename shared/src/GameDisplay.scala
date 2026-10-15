package htpc.protocol

import io.circe.Codec

enum GameScaling:
  case Linear, Fsr, Integer
object GameScaling:
  given Codec[GameScaling] = enumCodec(values)

/** Application-neutral profile. Zero dimensions mean the current output's native size. */
final case class GameDisplaySettings(enabled: Boolean = false, width: Int = 0, height: Int = 0,
  fps: Int = 60, scaling: GameScaling = GameScaling.Linear, sharpness: Int = 5, hud: Boolean = false) derives Codec.AsObject
final case class GameDisplaySupport(gamescope: Capability, mangoapp: Capability) derives Codec.AsObject
/** Append the application's argv to prefix; no shell parsing is needed by native launchers. */
final case class GameDisplayLaunch(application: String, prefix: Vector[String], output: OutputMode) derives Codec.AsObject

object GameDisplay:
  def validApplication(key: String): Boolean = key.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}")
  def arguments(s: GameDisplaySettings, output: OutputMode): Either[String, Vector[String]] =
    val native = s.width == 0 && s.height == 0
    val dimensions = native || s.width >= 320 && s.width <= 7680 && s.height >= 240 && s.height <= 4320
    val width = if native then output.width else s.width
    val height = if native then output.height else s.height
    if !dimensions || output.width <= 0 || output.height <= 0 then Left("无效的游戏或输出分辨率")
    else if !Set(0, 30, 40, 60, 90, 120).contains(s.fps) then Left("不支持的帧率上限")
    else if s.sharpness < 0 || s.sharpness > 20 then Left("FSR 锐度必须在 0–20 之间")
    else if s.scaling == GameScaling.Fsr && (width > output.width || height > output.height) then Left("FSR 渲染分辨率不能高于电视输出分辨率")
    else Right(Vector("--backend", "sdl", "-f", "-w", width.toString, "-h", height.toString,
      "-W", output.width.toString, "-H", output.height.toString, "-r", (if s.fps == 0 then (output.refreshMilliHz / 1000d).round.toInt else s.fps).toString,
      "--framerate-limit", s.fps.toString) ++
      (s.scaling match
        case GameScaling.Linear => Vector("-F", "linear")
        case GameScaling.Fsr => Vector("-F", "fsr", "--sharpness", s.sharpness.toString)
        case GameScaling.Integer => Vector("-S", "integer", "-F", "nearest")) ++
      (if s.hud then Vector("--mangoapp") else Vector.empty) ++ Vector("--"))
