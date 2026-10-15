package htpc.server

import cats.effect.{IO, Resource}
import htpc.protocol.*
import munit.CatsEffectSuite

class GameDisplaySuite extends CatsEffectSuite:
  private val output = OutputMode(1920, 1080, 60000)
  test("profile validation rejects unsupported dimensions, rates and FSR downscaling") {
    assert(GameDisplay.arguments(GameDisplaySettings(width = 0, height = 720), output).isLeft)
    assert(GameDisplay.arguments(GameDisplaySettings(fps = -1), output).isLeft)
    assert(GameDisplay.arguments(GameDisplaySettings(sharpness = 21), output).isLeft)
    assert(GameDisplay.arguments(GameDisplaySettings(width = 2560, height = 1440, scaling = GameScaling.Fsr), output).isLeft)
    assert(!GameDisplay.validApplication("x;touch /tmp/file"))
    assert(GameDisplay.validApplication("emulator-snes"))
  }
  test("FSR planning separates game and television resolution and includes the HUD") {
    val args = GameDisplay.arguments(GameDisplaySettings(enabled = true, width = 1600, height = 900, fps = 30, scaling = GameScaling.Fsr, hud = true), output).toOption.get
    def value(flag: String) = args(args.indexOf(flag) + 1)
    assertEquals(value("-w"), "1600"); assertEquals(value("-W"), "1920")
    assertEquals(value("-F"), "fsr"); assertEquals(value("--framerate-limit"), "30")
    assert(args.contains("--mangoapp")); assertEquals(args.last, "--")
    val unlimited = GameDisplay.arguments(GameDisplaySettings(fps = 0), output).toOption.get
    assertEquals(unlimited(unlimited.indexOf("-r") + 1), "60")
    assertEquals(unlimited(unlimited.indexOf("--framerate-limit") + 1), "0")
  }
  test("application identity is read from the compositor process, not its generic window title") {
    Resource.make(IO.blocking {
      val builder = new ProcessBuilder("/bin/sleep", "20")
      builder.environment().put("HTPC_APPLICATION", "local-test")
      builder.start()
    })(Resources.stopProcess).use { p => IO.blocking {
      assertEquals(Sway.application(p.pid()), Some("local-test"))
      assertEquals(Sway.application(Long.MaxValue), None)
    }}
  }
