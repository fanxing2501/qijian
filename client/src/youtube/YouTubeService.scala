package htpc.client.youtube

import com.raquo.laminar.api.L.*
import htpc.client.ApiClient
import htpc.client.tv.*
import htpc.protocol.*
import htpc.client.accounts.*
import htpc.youtube.*
import io.circe.{Decoder, Json}
import org.scalajs.dom
import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.control.NonFatal

enum YouTubeAuth:
  case Checking, SignedOut
  case SignedIn(name: String, avatar: Option[String])
  case Failed(message: String)
  def avatarLabel: String = this match
    case SignedIn(name, _) => name.take(1).toUpperCase
    case _ => "◎"

final class YouTubeService(val api: ApiClient):
  private val account = new YouTubeAccount(api)
  def request[A](op: AuthOp[A]): Future[A] = account.request(op)
  val auth = Var[YouTubeAuth](YouTubeAuth.Checking)
  val revision = Var(0)
  private val profile = "htpc-youtube-public"
  private var opening: Option[Future[ChromiumId]] = None
  private var pages = Set.empty[TabRef]
  def signedIn: Boolean = auth.now() match
    case YouTubeAuth.SignedIn(_, _) => true
    case _ => false

  def request[E, A](op: Request[E, A]): Future[A] = api.call(op).flatMap {
    case Right(value) => Future.successful(value)
    case Left(CallError.Domain(error: DomainError)) => Future.failed(RuntimeException(s"${error.code}: ${error.message}"))
    case Left(CallError.Domain(error)) => Future.failed(RuntimeException(error.toString))
    case Left(CallError.Transport(message)) => Future.failed(RuntimeException(s"无法连接 HTPC：$message"))
    case Left(CallError.Protocol(message)) => Future.failed(RuntimeException(s"返回数据校验失败：$message"))
  }

  def delay(ms: Int): Future[Unit] =
    val promise = Promise[Unit]()
    dom.window.setTimeout(() => promise.success(()), ms)
    promise.future

  def instance(): Future[ChromiumId] = opening.getOrElse {
    val created = request(ChromiumOp.ListInstance).flatMap { all =>
      all.find(i => i.profile.contains(profile) && i.state == InstanceState.Running) match
        case Some(instance) => Future.successful(instance.id)
        case None =>
          val cleanup = all.find(_.profile.contains(profile)).fold(Future.successful(()))(i => request(ChromiumOp.DeleteInstance(i.id)).map(_ => ()))
          cleanup.flatMap(_ => request(ChromiumOp.NewInstance(ChromiumOptions(name = "YouTube", profile = Some(profile), width = 1280, height = 800))).map(_.id))
            .recoverWith { case NonFatal(original) =>
              // Another browser frontend may have acquired the named profile concurrently.
              request(ChromiumOp.ListInstance).flatMap(_.find(i => i.profile.contains(profile) && i.state == InstanceState.Running)
                .fold(Future.failed[ChromiumId](original))(i => Future.successful(i.id)))
            }
    }
    opening = Some(created)
    created.onComplete(_ => opening = None)
    created
  }

  def newPage(url: String): Future[TabRef] = instance().flatMap { id =>
    request(ChromiumOp.NewTab(id)).flatMap { info =>
      pages += info.ref
      request(ChromiumOp.Goto(info.ref, Navigation(url, WaitUntil.Started, 30000))).map(_ => info.ref)
        .recoverWith { case NonFatal(error) => closePage(info.ref).flatMap(_ => Future.failed(error)) }
    }
  }

  def closePage(tab: TabRef): Future[Unit] =
    pages -= tab
    request(ChromiumOp.DeleteTab(tab)).map(_ => ()).recover { case NonFatal(_) => () }

  def releaseOnUnload(): Unit =
    account.close()
    pages.foreach(tab => api.releaseOnUnload(ChromiumOp.DeleteTab(tab)))
    pages = Set.empty

  def snapshot(tab: TabRef): Future[YouTubeSnapshot] =
    api.eval(tab, JsProgram(YouTubeScripts.snapshot, summon[Decoder[YouTubeSnapshot]]), EvalOptions(timeoutMs = 8000)).flatMap {
      case Right(value) => Future.successful(value)
      case Left(error) => Future.failed(RuntimeException(s"读取 YouTube 页面失败：$error"))
    }

  def awaitPage(tab: TabRef, timeoutMs: Int = 25000, cancelled: () => Boolean = () => false): Future[YouTubeSnapshot] =
    val deadline = scala.scalajs.js.Date.now() + timeoutMs
    def attempt(): Future[YouTubeSnapshot] = if cancelled() then Future.failed(RuntimeException("栏目已关闭")) else snapshot(tab).flatMap { value =>
      if cancelled() then Future.failed(RuntimeException("栏目已关闭"))
      else if value.ready && value.url.startsWith("https://www.youtube.com/") then Future.successful(value)
      else if scala.scalajs.js.Date.now() >= deadline then Future.failed(RuntimeException("YouTube 公开页面暂时无法读取，请稍后重试。"))
      else delay(400).flatMap(_ => attempt())
    }.recoverWith {
      case NonFatal(_) if !cancelled() && scala.scalajs.js.Date.now() < deadline => delay(400).flatMap(_ => attempt())
    }
    attempt()

  private def setAuth(value: YouTubeAuth): Unit = if auth.now() != value then { auth.set(value); revision.update(_ + 1) }
  def apiGet(path: String, query: Map[String, String]): Future[Json] = request(AuthOp.Get(Providers.YouTube, path, query))
  def refreshAccount(): Future[Unit] = request(AuthOp.Status(Providers.YouTube)).flatMap { status =>
    if !status.authorized then Future.successful(setAuth(YouTubeAuth.SignedOut))
    else apiGet("channels", Map("part" -> "snippet", "mine" -> "true")).map { json =>
      val channel = json.hcursor.downField("items").downArray.downField("snippet")
      setAuth(YouTubeAuth.SignedIn(channel.get[String]("title").getOrElse("YouTube 账号"), channel.downField("thumbnails").downField("default").get[String]("url").toOption))
    }.recover { case NonFatal(error) => setAuth(YouTubeAuth.Failed(error.getMessage)) }
  }.recover { case NonFatal(error) => setAuth(YouTubeAuth.Failed(error.getMessage)) }
  def signOut(): Future[Unit] = request(AuthOp.SignOut(Providers.YouTube)).map(_ => setAuth(YouTubeAuth.SignedOut))

  def subscriptions(toCard: YouTubeVideo => FeedCard): FeedSource[FeedCard] = new FeedSource[FeedCard]:
    def open(): FeedSession[FeedCard] = new FeedSession[FeedCard]:
      var closed = false
      def close(): Unit = closed = true
      def load(cursor: Option[Cursor], limit: Int): Future[Either[FeedError, FeedBatch[FeedCard]]] =
        if closed then Future.successful(Left(FeedError("栏目已关闭", false)))
        else
          val query = Map("part" -> "snippet", "mine" -> "true", "maxResults" -> math.min(6, limit).toString) ++ cursor.map(c => "pageToken" -> c.value)
          val result = for
            subs <- apiGet("subscriptions", query)
            ids = subs.hcursor.get[Vector[Json]]("items").getOrElse(Vector.empty).flatMap(_.hcursor.downField("snippet").downField("resourceId").get[String]("channelId").toOption)
            channels <- if ids.isEmpty then Future.successful(Json.obj()) else apiGet("channels", Map("part" -> "contentDetails", "id" -> ids.mkString(",")))
            playlists = channels.hcursor.get[Vector[Json]]("items").getOrElse(Vector.empty).flatMap(_.hcursor.downField("contentDetails").downField("relatedPlaylists").get[String]("uploads").toOption)
            pages <- Future.sequence(playlists.map(id => if closed then Future.successful(Json.obj()) else apiGet("playlistItems", Map("part" -> "snippet", "playlistId" -> id, "maxResults" -> "1"))))
          yield
            val videos = pages.flatMap(_.hcursor.get[Vector[Json]]("items").getOrElse(Vector.empty)).flatMap { item =>
              val s = item.hcursor.downField("snippet")
              for
                id <- s.downField("resourceId").get[VideoId]("videoId").toOption
                title <- s.get[String]("title").toOption
                image <- s.downField("thumbnails").downField("high").get[String]("url").toOption
              yield toCard(YouTubeVideo(id, title, s.get[String]("videoOwnerChannelTitle").getOrElse("YouTube"), image, None, None))
            }
            FeedBatch(videos, subs.hcursor.get[String]("nextPageToken").toOption.map(Cursor.apply))
          result.map(Right.apply).recover { case NonFatal(error) => Left(FeedError(error.getMessage)) }

  def source(url: String, toCard: YouTubeVideo => FeedCard): FeedSource[FeedCard] = new FeedSource[FeedCard]:
    def open(): FeedSession[FeedCard] = new FeedSession[FeedCard]:
      private var page: Option[Future[TabRef]] = None
      private var cached = Vector.empty[YouTubeVideo]
      private var more = true
      private var closed = false

      private def ownedPage(): Future[TabRef] = page.getOrElse {
        val created = newPage(url)
        page = Some(created)
        created.foreach(tab => if closed then closePage(tab))
        created.failed.foreach(_ => page = None)
        created
      }

      def load(cursor: Option[Cursor], limit: Int): Future[Either[FeedError, FeedBatch[FeedCard]]] =
        val offset = cursor.flatMap(_.value.toIntOption).getOrElse(0)
        if closed then Future.successful(Left(FeedError("栏目已关闭", false)))
        else ownedPage().flatMap { tab =>
          val loaded = if cached.size > offset then Future.successful(())
          else if offset == 0 then awaitPage(tab, cancelled = () => closed).map { value => cached = value.videos; more = value.hasMore }
          else
            request(ChromiumOp.ActivateTab(tab)).flatMap(_ => request(ChromiumOp.EvalJS(tab, YouTubeScripts.scrollMore, EvalOptions()))).flatMap { _ =>
              val deadline = scala.scalajs.js.Date.now() + 12000
              def collect(): Future[Unit] = delay(600).flatMap(_ => snapshot(tab)).flatMap { value =>
                cached = (cached ++ value.videos).distinctBy(_.id)
                more = value.hasMore
                if closed || cached.size > offset || !more then Future.successful(())
                else if scala.scalajs.js.Date.now() >= deadline then Future.failed(RuntimeException("加载更多超时，请重试。"))
                else collect()
              }
              collect()
            }
          loaded.map { _ =>
            val values = cached.slice(offset, offset + limit)
            val next = offset + values.size
            Right(FeedBatch(values.map(toCard), Option.when(values.nonEmpty && (next < cached.size || (more && next < 200)))(Cursor(next.toString))))
          }
        }.recover { case NonFatal(error) => Left(FeedError(Option(error.getMessage).getOrElse("YouTube 暂时无法连接"))) }

      def close(): Unit =
        if !closed then
          closed = true
          page.foreach(_.foreach(closePage))
