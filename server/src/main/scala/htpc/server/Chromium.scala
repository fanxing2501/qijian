package htpc.server

import cats.effect.{IO, Ref, Resource}
import cats.effect.std.{Dispatcher, Semaphore}
import cats.syntax.all.*
import htpc.protocol.*
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

private final case class BrowserEntry(info: ChromiumInstance, process: Process, cdp: Cdp, lock: Semaphore[IO], release: IO[Unit])

final class Chromium private(config: Config, events: EventHub, dispatcher: Dispatcher[IO], entries: Ref[IO, Map[ChromiumId, BrowserEntry]], lifecycle: Semaphore[IO]):
  def capability: Capability = config.chromium.fold(Capability(false, "Configure HTPC_CHROMIUM with a Chromium executable"))(p => Capability(true, p))

  def handle[A](op: ChromiumOp[A]): IO[Either[ChromiumError, A]] = run(op).attempt.map(_.left.map { t =>
    val f = Fault.from(t); ChromiumError(f.code, f.getMessage)
  })

  private def entry(id: ChromiumId): IO[BrowserEntry] = entries.get.flatMap(_.get(id)
    .liftTo[IO](Fault(ErrorCode.NotFound, "Chromium instance does not exist")))

  private def withEntry[A](id: ChromiumId)(f: BrowserEntry => IO[A]): IO[A] = entry(id).flatMap { e =>
    e.lock.permit.use(_ => entry(id) *> IO.raiseUnless(e.process.isAlive)(Fault(ErrorCode.Disconnected, "Chromium process exited")) *> f(e))
  }

  private def state(e: BrowserEntry): ChromiumInstance = e.info.copy(state = if e.process.isAlive then InstanceState.Running else InstanceState.Failed)

  private def processResource(id: ChromiumId, options: ChromiumOptions): Resource[IO, (Process, Path)] = for
    executable <- Resource.eval(config.chromium.liftTo[IO](Fault(ErrorCode.Unavailable, capability.detail)))
    profile <- Resource.make(Resources.profile(config.data.resolve("chromium"), options.profile, id.value)) { p =>
      if options.profile.isEmpty then Resources.deleteTree(p) else IO.unit
    }
    _ <- Resource.eval(IO.blocking(Files.deleteIfExists(profile.resolve("DevToolsActivePort"))))
    process <- Resource.make(IO.blocking {
      val args = Vector(executable, "--headless=new", "--remote-debugging-port=0", "--remote-debugging-address=127.0.0.1",
        s"--user-data-dir=$profile", "--no-first-run", "--no-default-browser-check", "--disable-background-networking",
        "--password-store=basic", s"--lang=${options.language}", s"--window-size=${options.width},${options.height}") ++
        options.proxy.toVector.map(p => s"--proxy-server=$p") ++ Vector("about:blank")
      new ProcessBuilder(args.asJava).redirectErrorStream(true)
        .redirectOutput(profile.resolve("chromium.log").toFile).start()
    })(Resources.stopProcess)
  yield (process, profile)

  private def endpoint(process: Process, profile: Path): IO[String] =
    def poll: IO[String] = IO.blocking {
      if !process.isAlive then throw Fault(ErrorCode.Execution, s"Chromium exited during startup; log: ${profile.resolve("chromium.log")}")
      val portFile = profile.resolve("DevToolsActivePort")
      Option.when(Files.exists(portFile))(Files.readAllLines(portFile).asScala.toVector)
        .filter(_.size >= 2).map(lines => s"ws://127.0.0.1:${lines(0)}${lines(1)}")
    }.flatMap(_.fold(IO.sleep(75.millis) *> poll)(IO.pure))
    poll.timeoutTo(30.seconds, IO.raiseError(Fault(ErrorCode.Timeout, "Chromium did not publish its CDP endpoint")))

  private def onEvent(id: ChromiumId, event: Json): Unit =
    val c = event.hcursor
    val method = c.get[String]("method").getOrElse("")
    val params = c.downField("params")
    val target = params.downField("targetInfo")
    val tab = target.get[String]("targetId").orElse(params.get[String]("targetId")).toOption.map(Id[TabKind](_))
    val kind = method match
      case "Target.targetCreated" if target.get[String]("type").contains("page") => Some(BrowserEventKind.TabCreated)
      case "Target.targetInfoChanged" if target.get[String]("type").contains("page") => Some(BrowserEventKind.TabChanged)
      case "Target.targetDestroyed" => Some(BrowserEventKind.TabDeleted)
      case "htpc.disconnected" => Some(BrowserEventKind.InstanceFailed)
      case _ => None
    kind.foreach(k => dispatcher.unsafeRunAndForget(events.publish(Topic.Chromium, BrowserEvent(k, id, tab))))

  private def create(options: ChromiumOptions): IO[ChromiumInstance] = lifecycle.permit.use { _ => IO.uncancelable { poll => for
    _ <- Fault.require(options.name.nonEmpty && options.name.length <= 120, "Name must contain 1–120 characters")
    _ <- Fault.require(options.width >= 320 && options.width <= 7680 && options.height >= 200 && options.height <= 4320, "Invalid viewport size")
    current <- entries.get
    _ <- IO.raiseWhen(options.profile.exists(p => current.values.exists(_.info.profile.contains(p))))(Fault(ErrorCode.Conflict, "Profile is already owned by an instance"))
    id = Resources.id[ChromiumKind]
    allocated <- poll((for
      pp <- processResource(id, options)
      (process, profile) = pp
      url <- Resource.eval(endpoint(process, profile))
      cdp <- Cdp.resource(url, onEvent(id, _))
      _ <- Resource.make(IO.unit)(_ => cdp.call("Browser.close", timeout = 5.seconds).attempt *>
        IO.blocking(process.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)).void)
      _ <- Resource.eval(cdp.call("Target.setDiscoverTargets", Json.obj("discover" -> true.asJson)))
      lock <- Resource.eval(Semaphore[IO](1))
    yield (process, cdp, lock)).allocated)
    ((process, cdp, lock), release) = allocated
    info = ChromiumInstance(id, options.name, options.profile, InstanceState.Running)
    _ <- entries.update(_ + (id -> BrowserEntry(info, process, cdp, lock, release)))
    _ <- events.publish(Topic.Chromium, BrowserEvent(BrowserEventKind.InstanceCreated, id))
  yield info }}

  private def delete(id: ChromiumId): IO[Done] = lifecycle.permit.use { _ => entry(id).flatMap { e =>
    e.lock.permit.use(_ => IO.uncancelable(_ => entries.update(_ - id) *> e.release *>
      events.publish(Topic.Chromium, BrowserEvent(BrowserEventKind.InstanceDeleted, id)).as(Done())))
  }}

  private def tabs(e: BrowserEntry): IO[Vector[TabInfo]] = e.cdp.call("Target.getTargets").flatMap { json =>
    IO.fromEither(json.hcursor.get[Vector[Json]]("targetInfos")).map(_.filter(_.hcursor.get[String]("type").contains("page")).map { t =>
      val c = t.hcursor
      TabInfo(TabRef(e.info.id, Id(c.get[String]("targetId").toOption.get)), c.get[String]("title").getOrElse(""), c.get[String]("url").getOrElse(""))
    })
  }

  private def session(e: BrowserEntry, tab: TabRef): Resource[IO, String] = for
    _ <- Resource.eval(tabs(e).flatMap(ts => IO.raiseUnless(ts.exists(_.ref == tab))(Fault(ErrorCode.NotFound, "Tab does not belong to this instance"))))
    s <- Resource.make(e.cdp.call("Target.attachToTarget", Json.obj("targetId" -> tab.tab.asJson, "flatten" -> true.asJson))
      .flatMap(j => IO.fromEither(j.hcursor.get[String]("sessionId")))) { s =>
      e.cdp.call("Target.detachFromTarget", Json.obj("sessionId" -> s.asJson)).attempt.void
    }
  yield s

  private def onTab[A](tab: TabRef)(f: (BrowserEntry, String) => IO[A]): IO[A] =
    withEntry(tab.instance)(e => session(e, tab).use(s => f(e, s)))

  private def pageInfo(e: BrowserEntry, s: String): IO[NavigationResult] =
    e.cdp.call("Runtime.evaluate", Json.obj("expression" -> "({url: location.href, title: document.title})".asJson,
      "returnByValue" -> true.asJson), Some(s)).flatMap(j => IO.fromEither(j.hcursor.downField("result").downField("value").as[NavigationResult]))

  private def waitPage(e: BrowserEntry, s: String, wait: WaitUntil, loader: Option[String], timeout: FiniteDuration, expectedUrl: Option[String] = None, previousLoader: Option[String] = None): IO[NavigationResult] =
    def poll: IO[NavigationResult] =
      val check = for
        tree <- e.cdp.call("Page.getFrameTree", session = Some(s))
        current = tree.hcursor.downField("frameTree").downField("frame").get[String]("loaderId").toOption
        ready <- e.cdp.call("Runtime.evaluate", Json.obj("expression" -> "({state: document.readyState, url: location.href})".asJson,
          "returnByValue" -> true.asJson), Some(s))
        value = ready.hcursor.downField("result").downField("value")
        state = value.get[String]("state").getOrElse("")
        loaded = wait match
          case WaitUntil.Started => true
          case WaitUntil.DOMContentLoaded => state == "interactive" || state == "complete"
          case WaitUntil.Load => state == "complete"
      yield loaded && loader.forall(l => current.contains(l)) && previousLoader.forall(l => !current.contains(l)) && expectedUrl.forall(u => value.get[String]("url").contains(u))
      check.attempt.flatMap {
        case Right(true) => pageInfo(e, s)
        case _ => IO.sleep(60.millis) *> poll
      }
    if wait == WaitUntil.Started then pageInfo(e, s)
    else poll.timeoutTo(timeout, IO.raiseError(Fault(ErrorCode.Timeout, "Navigation did not reach the requested readiness state")))

  private def navigate(tab: TabRef, n: Navigation): IO[NavigationResult] = for
    duration <- Fault.timeout(n.timeoutMs)
    _ <- Fault.require(n.url.matches("(?s)^(https?://|about:blank|data:).*"), "Navigation requires http(s), about:blank, or data URL")
    result <- onTab(tab) { (e, s) => for
      _ <- e.cdp.call("Page.enable", session = Some(s))
      result <- e.cdp.call("Page.navigate", Json.obj("url" -> n.url.asJson), Some(s), duration)
      _ <- result.hcursor.get[String]("errorText").toOption.fold(IO.unit)(err => IO.raiseError(Fault(ErrorCode.Execution, err)))
      page <- waitPage(e, s, n.waitUntil, result.hcursor.get[String]("loaderId").toOption, duration)
    yield page }
  yield result

  private def history(tab: TabRef, delta: Int): IO[NavigationResult] = onTab(tab) { (e, s) => for
    _ <- e.cdp.call("Page.enable", session = Some(s))
    history <- e.cdp.call("Page.getNavigationHistory", session = Some(s))
    index <- IO.fromEither(history.hcursor.get[Int]("currentIndex"))
    list <- IO.fromEither(history.hcursor.get[Vector[Json]]("entries"))
    target <- list.lift(index + delta).liftTo[IO](Fault(ErrorCode.Conflict, "No navigation history in that direction"))
    id <- IO.fromEither(target.hcursor.get[Int]("id"))
    _ <- e.cdp.call("Page.navigateToHistoryEntry", Json.obj("entryId" -> id.asJson), Some(s))
    page <- waitPage(e, s, WaitUntil.DOMContentLoaded, None, 30.seconds, target.hcursor.get[String]("url").toOption)
  yield page }

  private def evaluate(tab: TabRef, script: String, options: EvalOptions): IO[JsValue] = for
    duration <- Fault.timeout(options.timeoutMs)
    _ <- Fault.require(script.length <= 1024 * 1024, "Script exceeds 1 MiB")
    value <- onTab(tab) { (e, s) =>
      e.cdp.call("Runtime.evaluate", Json.obj("expression" -> script.asJson, "awaitPromise" -> options.awaitPromise.asJson,
        "returnByValue" -> true.asJson, "timeout" -> options.timeoutMs.asJson), Some(s), duration)
        .handleErrorWith {
          case f: Fault if f.code == ErrorCode.Timeout =>
            e.cdp.call("Runtime.terminateExecution", session = Some(s), timeout = 2.seconds).attempt *> IO.raiseError(f)
          case t => IO.raiseError(t)
        }.flatMap { json =>
          json.hcursor.downField("exceptionDetails").focus match
            case Some(error) => IO.raiseError(Fault(ErrorCode.Execution,
              error.hcursor.downField("exception").get[String]("description").orElse(error.hcursor.get[String]("text")).getOrElse("JavaScript failed")))
            case None =>
              val result = json.hcursor.downField("result")
              if result.get[String]("type").contains("undefined") then IO.pure(JsValue.Undefined)
              else result.downField("value").focus.map(v => IO.pure(JsValue.Value(v))).getOrElse(
                IO.raiseError(Fault(ErrorCode.Serialization, "JavaScript result is not JSON-serializable")))
        }
    }
  yield value

  private def run[A](op: ChromiumOp[A]): IO[A] = op match
    case ChromiumOp.ListInstance => entries.get.map(_.values.toVector.map(state).sortBy(_.id.value))
    case ChromiumOp.NewInstance(options) => create(options)
    case ChromiumOp.GetInstance(id) => entry(id).map(state)
    case ChromiumOp.DeleteInstance(id) => delete(id)
    case ChromiumOp.ListTab(id) => withEntry(id)(tabs)
    case ChromiumOp.NewTab(id) => withEntry(id) { e => for
      created <- e.cdp.call("Target.createTarget", Json.obj("url" -> "about:blank".asJson))
      target <- IO.fromEither(created.hcursor.get[String]("targetId"))
    yield TabInfo(TabRef(id, Id(target)), "", "about:blank") }
    case ChromiumOp.DeleteTab(tab) => onTab(tab) { (e, _) =>
      e.cdp.call("Target.closeTarget", Json.obj("targetId" -> tab.tab.asJson)).as(Done())
    }
    case ChromiumOp.Goto(tab, navigation) => navigate(tab, navigation)
    case ChromiumOp.Back(tab) => history(tab, -1)
    case ChromiumOp.Forward(tab) => history(tab, 1)
    case ChromiumOp.Reload(tab) => onTab(tab) { (e, s) => for
      _ <- e.cdp.call("Page.enable", session = Some(s))
      tree <- e.cdp.call("Page.getFrameTree", session = Some(s))
      previous = tree.hcursor.downField("frameTree").downField("frame").get[String]("loaderId").toOption
      _ <- e.cdp.call("Page.reload", session = Some(s))
      page <- waitPage(e, s, WaitUntil.DOMContentLoaded, None, 30.seconds, previousLoader = previous)
    yield page }
    case ChromiumOp.EvalJS(tab, script, options) => evaluate(tab, script, options)
    case ChromiumOp.TakeScreenshot(tab) => onTab(tab) { (e, s) =>
      e.cdp.call("Page.captureScreenshot", Json.obj("format" -> "png".asJson), Some(s))
        .flatMap(j => IO.fromEither(j.hcursor.get[String]("data"))).map(Screenshot("image/png", _))
    }
    case ChromiumOp.GetCookies(id) => withEntry(id) { e =>
      e.cdp.call("Storage.getCookies").flatMap(j => IO.fromEither(j.hcursor.get[Vector[Cookie]]("cookies")))
        .map(_.map(c => c.copy(expires = c.expires.filter(_ >= 0))))
    }
    case ChromiumOp.SetCookies(id, cookies) => withEntry(id) { e =>
      e.cdp.call("Storage.setCookies", Json.obj("cookies" -> Json.arr(cookies.map(_.asJson.deepDropNullValues)*))).as(Done())
    }
    case ChromiumOp.DeleteCookies(id, selector) => withEntry(id) { e =>
      Resource.make(e.cdp.call("Target.createTarget", Json.obj("url" -> "about:blank".asJson))
        .flatMap(j => IO.fromEither(j.hcursor.get[String]("targetId"))))(target =>
        e.cdp.call("Target.closeTarget", Json.obj("targetId" -> target.asJson)).attempt.void).use { target =>
        session(e, TabRef(id, Id(target))).use(s => e.cdp.call("Network.deleteCookies", selector.asJson.deepDropNullValues, Some(s)).as(Done()))
      }
    }

  def close: IO[Unit] = entries.getAndSet(Map.empty).flatMap(es => Resources.cleanup(es.values.map(_.release)))

object Chromium:
  def resource(config: Config, events: EventHub, dispatcher: Dispatcher[IO]): Resource[IO, Chromium] =
    Resource.make(for
      entries <- Ref.of[IO, Map[ChromiumId, BrowserEntry]](Map.empty)
      lock <- Semaphore[IO](1)
    yield new Chromium(config, events, dispatcher, entries, lock))(_.close)
