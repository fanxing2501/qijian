package htpc.server

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import htpc.protocol.*
import io.circe.Json
import io.circe.parser.parse
import java.net.{StandardProtocolFamily, UnixDomainSocketAddress}
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.channels.{ServerSocketChannel, SocketChannel}
import java.nio.file.{Files, Path}
import munit.CatsEffectSuite
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class SwaySuite extends CatsEffectSuite:
  private val tree = parse("""{"type":"root","nodes":[{"type":"workspace","name":"tv","nodes":[{"type":"con","id":42,"name":"Player","app_id":"player","window":null,"focused":true,"fullscreen_mode":1,"nodes":[]}],"floating_nodes":[{"type":"floating_con","id":43,"name":"XWayland","app_id":null,"window":123,"window_properties":{"class":"Game"},"focused":false,"fullscreen_mode":0}]}]}""").toOption.get

  test("window discovery includes native and floating XWayland clients") {
    val windows = Sway.windows(tree, "session")
    assertEquals(windows.size, 2)
    assertEquals(windows.map(_.workspace), Vector("tv", "tv"))
    assertEquals(windows(1).appId, Some("Game"))
    assert(windows.head.fullscreen)
  }

  private def socket: Resource[IO, (Path, ServerSocketChannel)] = for
    directory <- Resource.make(IO.blocking(Files.createTempDirectory(Path.of("/tmp/opencode"), "htpc-sway-")))(Resources.deleteTree)
    server <- Resource.make(IO.blocking {
      val s = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
      s.bind(UnixDomainSocketAddress.of(directory.resolve("ipc.sock")))
      s
    })(s => IO.blocking(s.close()))
  yield (directory.resolve("ipc.sock"), server)

  test("real Unix IPC framing handles fragmented responses") {
    socket.use { (path, server) =>
      val responder = IO.interruptibleMany {
        val peer = server.accept()
        try
          // Read the client's request without parsing its empty payload.
          val header = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN)
          while header.hasRemaining do peer.read(header)
          header.flip(); header.position(10)
          assertEquals(header.getInt(), 4)
          val payload = tree.noSpaces.getBytes(java.nio.charset.StandardCharsets.UTF_8)
          val response = ByteBuffer.allocate(14 + payload.length).order(ByteOrder.LITTLE_ENDIAN)
          response.put("i3-ipc".getBytes()).putInt(payload.length).putInt(4).put(payload)
          response.array().foreach(b => peer.write(ByteBuffer.wrap(Array(b))))
        finally peer.close()
      }
      for
        fiber <- responder.start
        result <- Resource.make(IO.interruptibleMany(SocketChannel.open(UnixDomainSocketAddress.of(path))))(s => IO.blocking(s.close())).use { client =>
          IO.interruptibleMany { SwayWire.write(client, 4, ""); SwayWire.read(client) }
        }
        _ <- fiber.joinWithNever
      yield assertEquals(result, (4, tree))
    }.timeout(5.seconds)
  }

  test("oversized IPC frames fail before allocating their payload") {
    socket.use { (path, server) => for
      peer <- IO.interruptibleMany(SocketChannel.open(UnixDomainSocketAddress.of(path)))
      accepted <- IO.interruptibleMany(server.accept())
      result <- (IO.interruptibleMany {
        val header = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN)
        header.put("i3-ipc".getBytes()).putInt(Int.MaxValue).putInt(4).flip()
        while header.hasRemaining do accepted.write(header)
        SwayWire.read(peer)
      }).attempt.guarantee(IO.blocking { peer.close(); accepted.close() })
    yield assert(result.left.exists { case f: Fault => f.code == ErrorCode.Protocol; case _ => false })
    }.timeout(5.seconds)
  }

  test("Sway adapter decodes snapshots, validates window ownership and preserves fractional refresh rates") {
    socket.use { (path, server) =>
      val commands = new java.util.concurrent.ConcurrentLinkedQueue[String]()
      val workspaces = parse("""[{"name":"tv","focused":true,"output":"HDMI-A-1"}]""").toOption.get
      val outputs = parse("""[{"name":"HDMI-A-1","active":true,"power":true,"current_mode":{"width":1920,"height":1080,"refresh":59940},"modes":[{"width":1920,"height":1080,"refresh":59940}]}]""").toOption.get
      val responder = IO.interruptibleMany {
        while true do
          val peer = server.accept()
          try
            var reading = true
            while reading do
              val header = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN)
              while reading && header.hasRemaining do
                if peer.read(header) < 0 then reading = false
              if reading then
                header.flip(); header.position(6)
                val length = header.getInt()
                val kind = header.getInt()
                val payload = ByteBuffer.allocate(length)
                while payload.hasRemaining do peer.read(payload)
                val text = new String(payload.array(), java.nio.charset.StandardCharsets.UTF_8)
                val reply = kind match
                  case 4 => tree
                  case 1 => workspaces
                  case 3 => outputs
                  case 0 => commands.add(text); parse("""[{"success":true}]""").toOption.get
                SwayWire.write(peer, kind, reply.noSpaces)
          finally peer.close()
      }
      Resource.make(responder.start)(_.cancel).use { _ => for
        hub <- EventHub.create
        sway = new Sway(Config.load.copy(swaySocket = Some(path)), hub)
        result <- sway.handle(SwayOp.Snapshot)
        snapshot = result.toOption.get
        focused <- sway.handle(SwayOp.Focus(snapshot.windows.head.ref))
        stale <- sway.handle(SwayOp.Focus(WindowRef("old-session", 42)))
        mode <- sway.handle(SwayOp.SetOutputMode("HDMI-A-1", OutputMode(1920, 1080, 59940)))
        unsupported <- sway.handle(SwayOp.SetOutputMode("HDMI-A-1", OutputMode(1, 1, 60)))
        gameDisplay = new GameDisplayService(Config.load.copy(gamescope = Some("/bin/true"), gamescopeLauncher = Some(Path.of("/bin/true")), mangoapp = None), sway)
        plan <- gameDisplay.prepare("local-game", GameDisplaySettings(enabled = true, width = 1280, height = 720, scaling = GameScaling.Fsr))
        missingHud <- gameDisplay.prepare("local-game", GameDisplaySettings(enabled = true, hud = true))
        invalidKey <- gameDisplay.prepare("invalid/key", GameDisplaySettings())
      yield {
        assertEquals(snapshot.outputs.head.currentMode, Some(OutputMode(1920, 1080, 59940)))
        assert(focused.isRight)
        assert(stale.left.exists(_.code == ErrorCode.Conflict))
        assert(mode.isRight)
        assert(unsupported.left.exists(_.code == ErrorCode.InvalidArgument))
        assertEquals(plan.toOption.get.application, "local-game")
        assertEquals(plan.toOption.get.output, OutputMode(1920, 1080, 59940))
        assert(plan.toOption.get.prefix.contains("1280"))
        assert(missingHud.left.exists(_.code == ErrorCode.Unavailable))
        assert(invalidKey.left.exists(_.code == ErrorCode.InvalidArgument))
        assertEquals(commands.iterator().asScala.toVector, Vector("[con_id=42] focus", "output \"HDMI-A-1\" mode 1920x1080@59.94Hz"))
      }
      }
    }.timeout(10.seconds)
  }
