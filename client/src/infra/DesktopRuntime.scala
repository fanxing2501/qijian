package htpc.client.infra

import htpc.client.ApiClient
import htpc.protocol.*
import io.circe.{Decoder, Json}
import io.circe.syntax.*
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

/** A configured local Chromium/CEF application's existing page, not a new browser. */
final class DesktopRuntime(api: ApiClient, service: String):
  private var sockets = Map.empty[String, CdpSocket]
  def status(): Future[HostService] = Rpc(api, HostOp.Status(service))
  def start(): Future[HostService] = Rpc(api, HostOp.Start(service))
  def targets(): Future[Vector[HostTarget]] = Rpc(api, HostOp.Targets(service)).map { all =>
    val gone = sockets.keySet -- all.map(_.id)
    gone.foreach(id => sockets(id).close())
    sockets --= gone
    all
  }
  private def socket(id: String): Future[CdpSocket] = sockets.get(id).filter(_.alive) match
    case Some(s) => Future.successful(s)
    case None => Rpc(api, HostOp.Socket(service, id)).map { endpoint =>
      val s = new CdpSocket(endpoint.path); sockets += id -> s; s
    }
  def evaluate[A: Decoder](target: String, expression: String): Future[A] = for
    s <- socket(target)
    result <- s.call("Runtime.evaluate", Json.obj("expression" -> expression.asJson, "awaitPromise" -> true.asJson,
      "returnByValue" -> true.asJson, "timeout" -> 15000.asJson))
    _ <- if result.hcursor.downField("exceptionDetails").succeeded then
      Future.failed(RuntimeException("本机应用接口执行失败，请重试。")) else Future.successful(())
    value <- Future.fromTry(result.hcursor.downField("result").get[A]("value").toTry)
  yield value
  def close(): Unit = { sockets.values.foreach(_.close()); sockets = Map.empty }
