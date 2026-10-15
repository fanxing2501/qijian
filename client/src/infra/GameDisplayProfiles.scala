package htpc.client.infra

import htpc.client.ApiClient
import htpc.protocol.*
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

/** Reused by Steam, local game and emulator Apps. Profiles belong to the HTPC. */
final class GameDisplayProfiles(api: ApiClient):
  private val storage = new ServerStorage(api)
  private def key(application: String) = ServerKey[GameDisplaySettings](StorageKey("game-display", application))
  def load(application: String): Future[(GameDisplaySettings, String)] = storage.get(key(application)).map((value, revision) => (value.getOrElse(GameDisplaySettings()), revision))
  def save(application: String, settings: GameDisplaySettings, revision: String): Future[StorageStamp] =
    storage.put(key(application), settings, Some(revision))
  def prepare(application: String, settings: GameDisplaySettings): Future[GameDisplayLaunch] = Rpc(api, SystemOp.PrepareGameDisplay(application, settings))
  def support(): Future[GameDisplaySupport] = Rpc(api, SystemOp.GameDisplayStatus)
