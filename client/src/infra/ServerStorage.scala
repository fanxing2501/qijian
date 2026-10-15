package htpc.client.infra

import htpc.client.ApiClient
import htpc.protocol.*
import io.circe.{Codec, Json}
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

object Rpc:
  def apply[E, A](api: ApiClient, op: Request[E, A]): Future[A] = api.call(op).flatMap {
    case Right(value) => Future.successful(value)
    case Left(CallError.Domain(e: DomainError)) => Future.failed(RuntimeException(s"${e.code}: ${e.message}"))
    case Left(CallError.Domain(_)) => Future.failed(RuntimeException("基础设施请求失败"))
    case Left(CallError.Transport(message)) => Future.failed(RuntimeException(s"无法连接 HTPC：$message"))
    case Left(CallError.Protocol(message)) => Future.failed(RuntimeException(s"返回数据校验失败：$message"))
  }

/** Typed keys add a Codec at the app boundary. The server stores only JSON. */
final case class ServerKey[A](key: StorageKey)(using val codec: Codec[A])
final class ServerStorage(api: ApiClient):
  def get[A](key: ServerKey[A]): Future[(Option[A], String)] = Rpc(api, StorageOp.Get(key.key)).flatMap { entry =>
    entry.value match
      case None => Future.successful((None, entry.revision))
      case Some(value) => Future.fromTry(key.codec.decodeJson(value).toTry).map(v => (Some(v), entry.revision))
  }
  def put[A](key: ServerKey[A], value: A, expected: Option[String] = None): Future[StorageStamp] =
    Rpc(api, StorageOp.Put(key.key, key.codec(value), expected))
  def inspect(key: StorageKey): Future[StorageStamp] = Rpc(api, StorageOp.Inspect(key))
  def delete(key: StorageKey): Future[StorageStamp] = Rpc(api, StorageOp.Delete(key))

  // Apps use these instead of get/put for credentials. Values travel server <-> remote Chromium only.
  def readInto(key: StorageKey, page: RemotePage, binding: String): Future[StorageStamp] =
    Rpc(api, StorageOp.ReadInto(key, page.world, binding))
  def writeFrom(key: StorageKey, page: RemotePage, expression: String, revision: String): Future[StorageStamp] =
    Rpc(api, StorageOp.WriteFrom(key, page.world, expression, revision))
  def captureCdp(key: StorageKey, browser: ChromiumId, method: String, params: Json, revision: String): Future[StorageStamp] =
    Rpc(api, StorageOp.CaptureCdp(key, browser, method, params, revision))
