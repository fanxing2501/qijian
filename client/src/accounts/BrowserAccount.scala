package htpc.client.accounts

import htpc.client.ApiClient
import htpc.client.infra.*
import htpc.protocol.*
import io.circe.Json
import io.circe.syntax.*
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

abstract class BrowserAccount(val api: ApiClient, val namespace: String):
  val storage = new ServerStorage(api)
  val browser = new BrowserRuntime(api, "htpc-" + namespace + "-account")
  val grantKey = StorageKey(namespace, "grant")
  private var tail: Future[Unit] = Future.successful(())
  protected def serialized[A](f: => Future[A]): Future[A] =
    val result = tail.transformWith(_ => f)
    tail = result.map(_ => ()).recover { case _ => () }
    result
  protected def now: Long = scala.scalajs.js.Date.now().toLong
  protected def newId: String = scala.util.Random.alphanumeric.take(32).mkString
  protected def qr(url: String): Future[String] = Rpc(api, SystemOp.QrCode(url)).map(i => s"data:${i.mimeType};base64,${i.base64}")
  protected def execute(page: RemotePage, body: String): Future[Json] = page.run(s"""(async()=>{try{const value=await(async()=>{$body})();return {ok:true,value};}catch(e){return {ok:false,message:e instanceof TypeError?'无法连接网站，请检查网络后重试。':String(e.message)}}})()""").flatMap { result =>
    if result.hcursor.get[Boolean]("ok").contains(true) then Future.successful(result.hcursor.downField("value").focus.getOrElse(Json.Null))
    else Future.failed(RuntimeException(result.hcursor.get[String]("message").getOrElse("浏览器请求失败")))
  }
  protected def load(page: RemotePage, key: StorageKey, binding: String): Future[StorageStamp] = storage.readInto(key, page, binding)
  protected def save(page: RemotePage, key: StorageKey, binding: String, stamp: StorageStamp): Future[StorageStamp] = storage.writeFrom(key, page, binding, stamp.revision)
  protected def wipe(page: RemotePage): Future[Unit] = page.run("globalThis._grant=null;globalThis._flow=null;globalThis._client=null;globalThis._reply=null;globalThis._transfer=null;globalThis._cookies=null;true").map(_ => ())
  protected def pendingKeys: Vector[StorageKey]
  def close(): Unit =
    pendingKeys.foreach(key => api.releaseOnUnload(StorageOp.Delete(key)))
    browser.close()
  def request[A](op: AuthOp[A]): Future[A]
