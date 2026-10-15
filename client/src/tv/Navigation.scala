package htpc.client.tv

import com.raquo.laminar.api.L.*
import com.raquo.laminar.codecs.StringAsIsCodec
import io.circe.parser.parse
import org.scalajs.dom
import scala.scalajs.js.URIUtils.{decodeURIComponent, encodeURIComponent}
import scala.scalajs.js

private trait ScrollSettings extends js.Object:
  val behavior: String
  val block: String
  val inline: String
@js.native private trait Scrollable extends js.Object:
  def scrollIntoView(options: ScrollSettings): Unit = js.native

final class Navigator(registry: AppRegistry, focus: FocusManager):
  val current = Var[Destination](Destination.Home)
  private var visited = Vector.empty[String]

  def key(destination: Destination): String = destination match
    case Destination.Home => "#/"
    case Destination.AppHome(app) => s"#/app/${app.metadata.id.value}"
    case Destination.Custom(app, route, _) => s"#/app/${app.metadata.id.value}/${encodeURIComponent(route.noSpaces)}"

  private def decode(hash: String): Destination =
    hash.stripPrefix("#/").split("/", 3).toList match
      case "app" :: id :: Nil => registry.get(AppId(id)).fold[Destination](Destination.Home)(Destination.AppHome.apply)
      case "app" :: id :: payload :: Nil => registry.get(AppId(id)) match
        case Some(app: RoutedApp) =>
          scala.util.Try(decodeURIComponent(payload)).toOption.flatMap(p => parse(p).toOption)
            .flatMap(app.routeCodec.decodeJson(_).toOption).fold[Destination](Destination.AppHome(app))(app.destination)
        case _ => Destination.Home
      case _ => Destination.Home

  private def changed(): Unit =
    val destination = decode(dom.window.location.hash)
    val nextKey = key(destination)
    if visited.lastOption != Some(nextKey) then
      focus.remember()
      if visited.dropRight(1).lastOption.contains(nextKey) then visited = visited.dropRight(1)
      else visited = visited :+ nextKey
      current.set(destination)
      focus.restore(nextKey)

  private val listener: scala.scalajs.js.Function1[dom.Event, Unit] = _ => changed()
  def start(): Unit = { dom.window.addEventListener("hashchange", listener); changed() }
  def stop(): Unit = dom.window.removeEventListener("hashchange", listener)
  def go(destination: Destination): Unit =
    focus.remember()
    val hash = key(destination)
    if dom.window.location.hash != hash then dom.window.location.hash = hash
    else focus.restore(hash)
  def back(): Unit =
    if visited.size > 1 then dom.window.history.back()
    else go(Destination.Home)

final class FocusManager:
  private var route = "#/"
  private var saved = Map.empty[String, String]
  private var restoreTimer = 0
  private var back: () => Unit = () => ()
  private var pending: Option[String] = None
  private var mutations: Option[dom.MutationObserver] = None

  def target(id: String, row: String): Modifier[HtmlElement] = Seq(
    htmlAttr("data-tv-focus", StringAsIsCodec) := id, htmlAttr("data-focus-row", StringAsIsCodec) := row
  )

  private def candidates: Vector[dom.HTMLElement] =
    val nodes = dom.document.querySelectorAll("[data-tv-focus]")
    (0 until nodes.length).map(i => nodes(i).asInstanceOf[dom.HTMLElement]).filter(e => e.offsetWidth > 0 && e.getAttribute("disabled") == null).toVector

  private def active: Option[dom.HTMLElement] = candidates.find(_ == dom.document.activeElement)

  def remember(): Unit = active.foreach(e => saved += route -> e.getAttribute("data-tv-focus"))
  def awaiting(prefix: String): Option[String] = pending.filter(_.startsWith(prefix))
  def navigate(direction: String): Unit = move(direction)
  def confirm(): Unit = { pending = None; active.foreach(_.click()) }
  def cancel(): Unit = { pending = None; back() }

  private def select(element: dom.HTMLElement): Unit =
    element.focus(new dom.FocusOptions { preventScroll = true })
    element.asInstanceOf[Scrollable].scrollIntoView(new ScrollSettings {
      val behavior = if dom.window.matchMedia("(prefers-reduced-motion: reduce)").matches then "auto" else "smooth"
      val block = "nearest"
      val inline = "center"
    })
    remember()

  def restore(key: String): Unit =
    route = key
    pending = saved.get(key)
    dom.window.clearTimeout(restoreTimer)
    restoreTimer = dom.window.setTimeout(() => {
      val items = candidates
      val last = saved.get(key).flatMap(id => items.find(_.getAttribute("data-tv-focus") == id))
      if last.nonEmpty then pending = None
      last.orElse(items.find(_.getAttribute("data-autofocus") == "true")).orElse(items.headOption).foreach(select)
      pending.foreach { id =>
        val feeds = dom.document.querySelectorAll("[data-feed-id]")
        (0 until feeds.length).map(feeds(_)).find(e => id.startsWith(e.getAttribute("data-feed-id") + ":"))
          .foreach(_.asInstanceOf[dom.HTMLElement].scrollIntoView(false))
      }
    }, 0)

  private def move(key: String): Unit =
    pending = None
    val items = candidates
    active match
      case None => items.headOption.foreach(select)
      case Some(from) =>
        val rect = from.getBoundingClientRect()
        val row = from.getAttribute("data-focus-row")
        val siblings = items.filter(_.getAttribute("data-focus-row") == row)
        val horizontal = if key == "ArrowLeft" || key == "ArrowRight" then
          siblings.lift(siblings.indexOf(from) + (if key == "ArrowLeft" then -1 else 1))
        else None
        val vertical = if key == "ArrowUp" || key == "ArrowDown" then
          val cx = rect.left + rect.width / 2
          val cy = rect.top + rect.height / 2
          items.filterNot(_.getAttribute("data-focus-row") == row).flatMap { e =>
            val r = e.getBoundingClientRect()
            val dx = math.abs(r.left + r.width / 2 - cx)
            val dy = r.top + r.height / 2 - cy
            Option.when(if key == "ArrowDown" then dy > 10 else dy < -10)((e, math.abs(dy) + dx * 1.8))
          }.sortBy(_._2).headOption.map(_._1)
        else None
        horizontal.orElse(vertical).foreach(select)

  private val listener: scala.scalajs.js.Function1[dom.KeyboardEvent, Unit] = event =>
    val editing = Option(dom.document.activeElement).exists(e => e.matches("input,textarea,select,[contenteditable=true]"))
    val multiline = Option(dom.document.activeElement).exists(e => e.matches("textarea,select,[contenteditable=true]"))
    val remote = Option(dom.document.activeElement).exists(_.matches("[data-remote-input]"))
    val navigationKey = event.key == "Escape" || (!multiline && Set("ArrowUp", "ArrowDown").contains(event.key))
    if !event.defaultPrevented && !remote && (!editing || navigationKey) then event.key match
      case "ArrowLeft" | "ArrowRight" | "ArrowUp" | "ArrowDown" => event.preventDefault(); move(event.key)
      case "Escape" | "Backspace" | "BrowserBack" => event.preventDefault(); back()
      case "Enter" | " " if active.nonEmpty => event.preventDefault(); active.foreach(_.click())
      case _ => ()

  private val pointer: js.Function1[dom.Event, Unit] = _ => pending = None
  def start(onBack: () => Unit): Unit =
    back = onBack
    dom.document.addEventListener("keydown", listener)
    dom.document.addEventListener("pointerdown", pointer)
    val observer = new dom.MutationObserver((_, _) => pending.foreach { id =>
      candidates.find(_.getAttribute("data-tv-focus") == id).foreach { element => pending = None; select(element) }
    })
    observer.observe(dom.document.getElementById("app"), new dom.MutationObserverInit { childList = true; subtree = true })
    mutations = Some(observer)
  def stop(): Unit =
    dom.document.removeEventListener("keydown", listener)
    dom.document.removeEventListener("pointerdown", pointer)
    dom.window.clearTimeout(restoreTimer)
    mutations.foreach(_.disconnect())
