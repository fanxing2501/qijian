package htpc.client.steam

import com.raquo.laminar.api.L.*
import htpc.client.ApiClient
import htpc.client.tv.*
import htpc.protocol.*
import htpc.client.accounts.*
import htpc.steam.*
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.control.NonFatal

final class SteamService(api: ApiClient):
  private val auth = new SteamLoginSession(api)
  def close(): Unit = auth.close()
  val account = Var(Option.empty[SteamAccount])
  val checking = Var(true)
  val problem = Var(Option.empty[String])
  val total = Var(Option.empty[Int])
  val revision = Var(0)
  private var generation = 0
  private var cached: Option[Future[Vector[SteamGame]]] = None

  def request[A](op: AuthOp[A]): Future[A] = auth.request(op)

  private def invalidate(): Unit =
    generation += 1; cached = None; total.set(None); revision.update(_ + 1)

  def refreshAccount(): Future[Unit] =
    val run = generation
    checking.set(true); problem.set(None)
    request(AuthOp.Status(Providers.Steam)).flatMap { status =>
      if !status.authorized then Future.successful(None)
      else request(AuthOp.Get(Providers.Steam, "account", Map.empty)).flatMap(json =>
        Future.fromTry(json.as[SteamAccount].toTry)).map(Some(_))
    }.map { value => if run == generation then
      checking.set(false)
      if account.now() != value then { account.set(value); invalidate() }
    }.recover { case NonFatal(e) => if run == generation then { checking.set(false); problem.set(Some(e.getMessage)) } }

  def signOut(): Future[Unit] = request(AuthOp.SignOut(Providers.Steam)).map { _ =>
    account.set(None); checking.set(false); problem.set(None); invalidate()
  }

  def refreshLibrary(): Unit = invalidate()

  def library(): Future[Vector[SteamGame]] = cached.getOrElse {
    val run = generation
    val loading = request(AuthOp.Get(Providers.Steam, "IPlayerService/GetOwnedGames/v1", Map(
      "include_appinfo" -> "true", "include_played_free_games" -> "true", "include_free_sub" -> "true",
      "skip_unvetted_apps" -> "false", "include_extended_appinfo" -> "true", "include_family_licenses" -> "false", "language" -> "schinese")))
      .flatMap(json => SteamLibrary.decode(json).fold(e => Future.failed(RuntimeException(e)), Future.successful))
      .map { games =>
        if run != generation then throw RuntimeException("Steam 账号或游戏库已更新，请重新加载。")
        total.set(Some(games.size)); games
      }
    cached = Some(loading)
    loading.failed.foreach(_ => if run == generation then cached = None)
    loading
  }

  def source(toCard: SteamGame => FeedCard): FeedSource[FeedCard] = new FeedSource[FeedCard]:
    def open(): FeedSession[FeedCard] = new FeedSession[FeedCard]:
      private var closed = false
      private val run = generation
      def close(): Unit = closed = true
      def load(cursor: Option[Cursor], limit: Int): Future[Either[FeedError, FeedBatch[FeedCard]]] =
        library().map { games =>
          if closed || run != generation then Left(FeedError("游戏库已更新。"))
          else
            val start = cursor.flatMap(_.value.toIntOption).getOrElse(0).max(0)
            val end = math.min(games.size, start + math.max(1, math.min(100, limit)))
            Right(FeedBatch(games.slice(start, end).map(toCard), Option.when(end < games.size)(Cursor(end.toString))))
        }.recover { case NonFatal(error) => Left(FeedError(error.getMessage)) }
