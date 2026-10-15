package htpc.client.tv

import com.raquo.laminar.api.L.*
import htpc.client.ApiClient
import io.circe.{Codec, Json}
import scala.concurrent.Future

final case class AppId(value: String)
final case class FeedId(value: String)
final case class CardId(value: String)
final case class Cursor(value: String)
final case class ImageSource(url: String, alt: String, fallback: Option[String] = None)
final case class Color(value: String)
final case class AppMetadata(id: AppId, name: String, icon: ImageSource, background: Option[ImageSource], accentColor: Color, description: String)

trait App:
  def metadata: AppMetadata
  def feeds: LazyList[FeedItem]
  def mainPage: Page

trait Page:
  def render(context: PageContext): HtmlElement
object Page:
  def apply(view: PageContext => HtmlElement): Page = new Page:
    def render(context: PageContext) = view(context)

/** The route codec and resolver share the same path-dependent Route type. */
trait RoutedApp extends App:
  type Route
  def routeCodec: Codec[Route]
  def resolve(route: Route): Page
  final def destination(route: Route): Destination = Destination.Custom(this, routeCodec(route), resolve(route))

enum Destination:
  case Home
  case AppHome(app: App)
  case Custom(app: App, route: Json, page: Page)

enum CardLayout:
  case Poster, Landscape, Square

final case class FeedCard(id: CardId, title: String, subtitle: String, artwork: ImageSource, badge: Option[String], destination: Destination)
final case class FeedError(message: String, retryable: Boolean = true)
final case class FeedBatch[A](items: Vector[A], nextCursor: Option[Cursor])

/** Definitions are lazy; each mounted view owns an independently disposable session. */
trait FeedSource[A]:
  def open(): FeedSession[A]
trait FeedSession[A]:
  def load(cursor: Option[Cursor], limit: Int): Future[Either[FeedError, FeedBatch[A]]]
  def close(): Unit

final case class FeedItem(id: FeedId, title: String, subtitle: String, layout: CardLayout, source: FeedSource[FeedCard])

final class AppRegistry(val apps: Vector[App]):
  require(apps.map(_.metadata.id).distinct.size == apps.size, "App IDs must be unique")
  def get(id: AppId): Option[App] = apps.find(_.metadata.id == id)

final case class PageContext(api: ApiClient, navigation: Navigator, focus: FocusManager)
