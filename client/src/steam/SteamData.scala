package htpc.steam

import io.circe.{Codec, Json}

final case class SteamAccount(steamid: String, account_name: String) derives Codec.AsObject
final case class SteamGame(appId: Int, name: String, lastPlayed: Long, playtimeMinutes: Long,
  recentMinutes: Long) derives Codec.AsObject:
  def cover: String = s"https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/$appId/library_600x900_2x.jpg"

object SteamLibrary:
  /** Runs in Scala.js. Own the full snapshot before paginating, so sorting is global. */
  def decode(json: Json): Either[String, Vector[SteamGame]] =
    val c = json.hcursor
    for
      count <- c.get[Int]("game_count").left.map(_ => "Steam 未返回游戏库，请重新登录后重试。")
      items <- if count == 0 then Right(Vector.empty[Json]) else c.get[Vector[Json]]("games").left.map(_ => "Steam 未返回游戏列表，请检查账号的游戏库访问权限。")
      games <- items.foldLeft[Either[String, Vector[SteamGame]]](Right(Vector.empty)) { (acc, value) =>
        val v = value.hcursor
        for
          all <- acc
          id <- v.get[Int]("appid").left.map(_ => "Steam 游戏数据缺少 AppID。")
          _ <- Either.cond(id > 0, (), "Steam 返回了无效 AppID。")
        yield all :+ SteamGame(id, v.get[String]("name").toOption.filter(_.nonEmpty).getOrElse(s"Steam 游戏 $id"),
          math.max(0L, v.get[Long]("rtime_last_played").getOrElse(0L)), math.max(0L, v.get[Long]("playtime_forever").getOrElse(0L)),
          math.max(0L, v.get[Long]("playtime_2weeks").getOrElse(0L)))
      }
      _ <- Either.cond(count >= 0 && games.size >= count, (), "Steam 返回的游戏库不完整，请重新加载。")
    yield games.distinctBy(_.appId).sortBy(g => (-g.lastPlayed, g.name.toLowerCase, g.appId))
