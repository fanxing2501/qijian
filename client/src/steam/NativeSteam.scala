package htpc.client.steam

import com.raquo.laminar.api.L.*
import htpc.client.ApiClient
import htpc.client.infra.{DesktopRuntime, Rpc}
import htpc.client.tv.PageContext
import htpc.protocol.*
import io.circe.{Codec, Json}
import io.circe.syntax.*
import org.scalajs.dom
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

final case class LocalGame(appId: Int, installed: Boolean, running: Boolean, downloading: Boolean, paused: Boolean,
  queued: Boolean, bytesDone: Double, bytesTotal: Double, rate: Double, percent: Double, error: String,
  task: String, taskDone: String, taskTotal: String) derives Codec.AsObject
final case class LocalFolder(index: Int, path: String, free: String, mounted: Boolean) derives Codec.AsObject
final case class LocalInstall(stage: Int, appIds: Vector[Int], required: Double, available: Double, folder: Int, error: String) derives Codec.AsObject
final case class LocalSteamState(loggedIn: Boolean, accountName: String, steamId: String, games: Vector[LocalGame],
  folders: Vector[LocalFolder], install: Option[LocalInstall], launch: Option[LocalLaunch]) derives Codec.AsObject
final case class LocalLaunch(appId: Int, startedAt: Double, seenRunning: Boolean) derives Codec.AsObject
final case class LocalEula(id: String, version: Int, title: String, text: String) derives Codec.AsObject
final case class LocalQr(image: Option[String], state: String) derives Codec.AsObject
final case class LocalActionResult(ok: Boolean, error: String) derives Codec.AsObject

final class NativeSteam(api: ApiClient):
  private val runtime = new DesktopRuntime(api, "steam")
  val state = Var(Option.empty[LocalSteamState])
  val qrImage = Var(Option.empty[String])
  val qrState = Var("loading")
  val problem = Var(Option.empty[String])
  val busy = Var(false)
  val gameActive = Var(false)
  val displayProblem = Var(Option.empty[String])
  private val displayController = dom.window.location.search.contains("display=1")
  private var watched = Set.empty[Int]
  private var target: Option[String] = None
  private var timer = 0
  private var mounted = false
  private var polling = false
  private var context: Option[PageContext] = None
  private var launched: Option[Int] = None
  private var gameWindow: Option[WindowRef] = None
  private var previousWindow: Option[WindowRef] = None
  private var seenRunning = false
  private var launchTime = 0.0
  private var loginVisible = false
  private var refreshQrPending = false

  def openLogin(): Unit = { loginVisible = true; refreshQr() }
  def closeLogin(): Unit = { loginVisible = false; qrImage.set(None) }
  def refreshQr(): Unit =
    refreshQrPending = true; qrImage.set(None); qrState.set("loading"); poll()

  def watch(id: Int): Unit = { watched += id; if mounted then poll() }
  def mount(ctx: PageContext): Unit = if !mounted then
    mounted = true; context = Some(ctx); poll()
  def close(): Unit =
    mounted = false; dom.window.clearTimeout(timer); runtime.close()

  private def manageWindows(value: LocalSteamState): Future[Unit] =
    if displayController && value.launch.isEmpty && !seenRunning then launched = None
    if displayController && launched.isEmpty then value.launch.foreach { launch =>
      launched = Some(launch.appId); launchTime = launch.startedAt; seenRunning = launch.seenRunning
      context.foreach(_.focus.remember())
    }
    gameActive.set(value.games.exists(_.running))
    if !displayController then Future.successful(()) else launched.fold(Future.successful(())) { id =>
    Rpc(api, SwayOp.Snapshot).flatMap { display =>
      val window = display.windows.find(_.appId.exists(_.equalsIgnoreCase(s"steam_app_$id")))
      val running = value.games.find(_.appId == id).exists(_.running)
      if previousWindow.isEmpty then previousWindow = display.windows.find(_.appId.contains("htpc-next")).map(_.ref)
      if running || window.nonEmpty then seenRunning = true
      window match
        case Some(w) if !gameWindow.contains(w.ref) =>
          Rpc(api, SwayOp.Move(w.ref, "game")).flatMap(_ => Rpc(api, SwayOp.SetFullscreen(w.ref, true)))
            .flatMap(_ => Rpc(api, SwayOp.Focus(w.ref))).map { _ => gameWindow = Some(w.ref); displayProblem.set(None) }
        case None if seenRunning && !running =>
          launched = None; gameWindow = None
          Rpc(api, SwayOp.ActivateWorkspace("home")).flatMap { _ =>
            previousWindow.filter(ref => display.windows.exists(_.ref == ref)).fold(Future.successful(()))(ref =>
              Rpc(api, SwayOp.SetFullscreen(ref, true)).flatMap(_ => Rpc(api, SwayOp.Focus(ref))).map(_ => ()))
          }.flatMap { _ =>
            context.foreach(ctx => ctx.focus.restore(ctx.navigation.key(ctx.navigation.current.now())))
            clearLaunch(id)
          }
        case None if !seenRunning && value.games.find(_.appId == id).forall(_.task.isEmpty) && scala.scalajs.js.Date.now() - launchTime > 120000 =>
          launched = None; displayProblem.set(Some("游戏尚未创建窗口，请查看启动状态后重试。")); clearLaunch(id)
        case _ => Future.successful(())
    }.recover { case _ => displayProblem.set(Some("游戏窗口切换失败，请检查 Sway 会话。")) }
  }

  private def evaluate[A: io.circe.Decoder](expression: String): Future[A] =
    target.fold(Future.failed[A](RuntimeException("本机 Steam 尚未连接。")))(runtime.evaluate[A](_, expression))
  private def clearLaunch(id: Int): Future[Unit] = evaluate[Boolean](s"(()=>{if(window.__qijianNative?.launch?.appId===$id)window.__qijianNative.launch=null;return true})()").map(_ => ())
  def loadEulas(id: Int): Future[Vector[LocalEula]] = evaluate(s"(${NativeSteamScripts.eulas})($id)")
  def acceptEulas(id: Int, eulas: Vector[LocalEula], account: String): Future[Unit] =
    val versions = eulas.map(e => Json.obj("id" -> e.id.asJson, "version" -> e.version.asJson))
    evaluate[LocalActionResult](s"(${NativeSteamScripts.acceptEulas})($id,${versions.asJson.noSpaces},${account.asJson.noSpaces})")
      .flatMap(r => if r.ok then { poll(); Future.successful(()) } else Future.failed(RuntimeException(r.error)))

  def poll(): Unit = if mounted && !polling then
    polling = true; dom.window.clearTimeout(timer)
    val refresh = for
      targets <- runtime.targets()
      shared <- targets.find(_.title == "SharedJSContext").map(Future.successful)
        .getOrElse(Future.failed(RuntimeException("本机 Steam 正在启动或更新，请稍候。")))
      _ = target = Some(shared.id)
      value <- runtime.evaluate[LocalSteamState](shared.id, s"(${NativeSteamScripts.snapshot})(${watched.toVector.asJson.noSpaces})")
      qr <- if value.loggedIn || !loginVisible then Future.successful(LocalQr(None, "idle")) else targets.find(t => t.id != shared.id && t.kind == "page")
        .fold(Future.successful(LocalQr(None, "loading"))) { t =>
          val refresh = refreshQrPending
          refreshQrPending = false
          runtime.evaluate[LocalQr](t.id, s"(${NativeSteamScripts.qr})($refresh)").recover { case _ => LocalQr(None, "unavailable") }
        }
      _ <- manageWindows(value)
    yield (value, qr)
    refresh.onComplete { result =>
      polling = false
      if mounted then
        result match
          case Success((value, qr)) => state.set(Some(value)); qrImage.set(if loginVisible then qr.image else None); qrState.set(qr.state); problem.set(None)
          case Failure(_) => target = None; state.set(None); qrImage.set(None); problem.set(Some("本机 Steam 尚未就绪，可启动客户端后重试。"))
        timer = dom.window.setTimeout(() => poll(), if result.isSuccess then 2000 else 5000)
    }

  def start(): Unit = if !busy.now() then
    busy.set(true)
    runtime.start().onComplete { result => busy.set(false); result.failed.foreach(e => problem.set(Some(e.getMessage))); poll() }

  def perform(op: String, id: Int, folder: Int, expectedAccount: String): Future[Unit] =
    val action = for
      t <- target.fold(Future.failed[String](RuntimeException("本机 Steam 尚未连接。")))(Future.successful)
      _ <- if op == "play" && displayController then Rpc(api, SwayOp.Snapshot).map { s =>
        previousWindow = s.windows.find(_.focused).filterNot(_.appId.exists(_.equalsIgnoreCase("steam"))).map(_.ref)
        context.foreach(_.focus.remember())
      } else Future.successful(())
      r <- runtime.evaluate[LocalActionResult](t, s"(${NativeSteamScripts.action})(${op.asJson.noSpaces},$id,$folder,${expectedAccount.asJson.noSpaces})")
      _ <- if r.ok then Future.successful(()) else Future.failed(RuntimeException(r.error))
    yield
      if op == "play" && displayController then { launched = Some(id); seenRunning = false; gameWindow = None; launchTime = scala.scalajs.js.Date.now() }
      poll()
    action

  def returnToGame(id: Int): Future[Unit] = Rpc(api, SwayOp.Snapshot).flatMap { s =>
    s.windows.find(_.appId.exists(_.equalsIgnoreCase(s"steam_app_$id")))
      .fold(Future.failed[Unit](RuntimeException("暂未找到游戏窗口。")))(w => Rpc(api, SwayOp.Focus(w.ref)).map(_ => ()))
  }

object NativeSteam:
  def size(bytes: Double): String = if bytes >= 1024 * 1024 * 1024 then f"${bytes / (1024 * 1024 * 1024)}%.1f GB" else f"${bytes / (1024 * 1024)}%.1f MB"
  def task(game: LocalGame): String =
    val name = game.task match
      case "ProcessingShaderCache" => "正在准备着色器缓存"
      case "ProcessingInstallScript" => "正在安装首次运行组件"
      case "SynchronizingCloud" | "CloudSync" => "正在同步云存档"
      case "DownloadingDepots" | "UpdatingApp" => "正在更新游戏组件"
      case "CreatingProcess" | "WaitingGameWindow" => "正在打开游戏"
      case _ => "正在准备游戏"
    val percent = for done <- game.taskDone.toDoubleOption; total <- game.taskTotal.toDoubleOption if total > 0
      yield s" · ${(done * 100 / total).max(0).min(100).toInt}%"
    name + percent.getOrElse("")
