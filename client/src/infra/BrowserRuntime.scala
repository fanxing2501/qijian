package htpc.client.infra

import htpc.client.ApiClient
import htpc.protocol.*
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import org.scalajs.dom
import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.control.NonFatal

final class CdpSocket(path: String):
  private val ws = new dom.WebSocket((if dom.window.location.protocol == "https:" then "wss://" else "ws://") + dom.window.location.host + path)
  private val ready = Promise[Unit]()
  private var sequence = 0
  private var pending = Map.empty[Int, (Promise[Json], Int)]
  private var listeners = Vector.empty[Json => Unit]
  var alive = true
  ws.onopen = _ => ready.trySuccess(())
  private def failed(): Unit =
    alive = false
    val error = RuntimeException("Chromium 连接已断开，请重试。")
    ready.tryFailure(error)
    pending.values.foreach { (p, timer) => dom.window.clearTimeout(timer); p.tryFailure(error) }
    pending = Map.empty
  ws.onerror = _ => { failed(); ws.close() }
  ws.onclose = _ => failed()
  ws.onmessage = event => parse(event.data.toString).foreach { json =>
    json.hcursor.get[Int]("id").toOption match
      case Some(id) => pending.get(id).foreach { (promise, timer) =>
        pending -= id; dom.window.clearTimeout(timer)
        json.hcursor.downField("error").focus match
          case Some(error) => promise.tryFailure(RuntimeException(error.hcursor.get[String]("message").getOrElse("CDP request failed")))
          case None => promise.trySuccess(json.hcursor.downField("result").focus.getOrElse(Json.obj()))
      }
      case None => listeners.foreach(_(json))
  }
  def listen(f: Json => Unit): () => Unit =
    listeners :+= f
    () => listeners = listeners.filterNot(_ eq f)
  def call(method: String, params: Json = Json.obj(), session: Option[String] = None): Future[Json] =
    val result = Promise[Json]()
    sequence += 1; val id = sequence
    val timer = dom.window.setTimeout(() => { pending -= id; result.tryFailure(RuntimeException(s"CDP $method 超时")) }, 125000)
    pending += id -> (result, timer)
    ready.future.foreach { _ => if alive && pending.contains(id) then ws.send(Json.obj("id" -> id.asJson,
      "method" -> method.asJson, "params" -> params).deepMerge(session.fold(Json.obj())(s => Json.obj("sessionId" -> s.asJson))).noSpaces) }
    if !alive then { dom.window.clearTimeout(timer); pending -= id; result.tryFailure(RuntimeException("Chromium 连接已断开")) }
    result.future
  def close(): Unit = { failed(); listeners = Vector.empty; ws.close() }

final class RemotePage(val world: BrowserWorld, val cdp: CdpSocket, val session: String, val context: Int):
  def run(expression: String): Future[Json] = cdp.call("Runtime.evaluate", Json.obj("expression" -> expression.asJson,
    "contextId" -> context.asJson, "awaitPromise" -> true.asJson, "returnByValue" -> true.asJson, "timeout" -> 110000.asJson), Some(session)).flatMap { json =>
    if json.hcursor.downField("exceptionDetails").succeeded then Future.failed(RuntimeException("浏览器脚本执行失败，请重试。"))
    else Future.successful(json.hcursor.downField("result").downField("value").focus.getOrElse(Json.Null))
  }

/** Site-neutral client-side CDP driver. Fetches run at their real origin in empty,
  * intercepted documents; no site JavaScript shares the credential execution world. */
final class BrowserRuntime(api: ApiClient, profile: String):
  private var connection: Option[Future[(ChromiumId, CdpSocket, String)]] = None
  private var pages = Map.empty[String, Future[RemotePage]]
  private def connect(): Future[(ChromiumId, CdpSocket, String)] = connection.getOrElse {
    val opened = for
      all <- Rpc(api, ChromiumOp.ListInstance)
      instance <- all.find(i => i.profile.contains(profile) && i.state == InstanceState.Running) match
        case Some(i) => Future.successful(i)
        case None =>
          val cleanup = all.find(_.profile.contains(profile)).fold(Future.successful(()))(i => Rpc(api, ChromiumOp.DeleteInstance(i.id)).map(_ => ()))
          cleanup.flatMap(_ => Rpc(api, ChromiumOp.NewInstance(ChromiumOptions(name = profile, profile = Some(profile)))))
            .recoverWith { case NonFatal(e) => Rpc(api, ChromiumOp.ListInstance).flatMap(_.find(i => i.profile.contains(profile) && i.state == InstanceState.Running).fold(Future.failed[ChromiumInstance](e))(Future.successful)) }
      endpoint <- Rpc(api, ChromiumOp.GetSocket(instance.id))
      socket = new CdpSocket(endpoint.path)
      context <- socket.call("Target.createBrowserContext", Json.obj("disposeOnDetach" -> true.asJson)).recoverWith { case NonFatal(e) => socket.close(); Future.failed(e) }
    yield (instance.id, socket, context.hcursor.get[String]("browserContextId").toOption.get)
    connection = Some(opened)
    opened.failed.foreach(_ => { connection = None; pages = Map.empty })
    opened
  }
  def info(): Future[(ChromiumId, CdpSocket, String)] = connect().flatMap { value =>
    if value._2.alive then Future.successful(value)
    else { connection = None; pages = Map.empty; connect() }
  }
  def page(origin: String): Future[RemotePage] = info().flatMap { (id, cdp, browserContext) => pages.getOrElse(origin, {
    val url = origin + "/__htpc_runtime__"
    val created = for
      target <- cdp.call("Target.createTarget", Json.obj("url" -> "about:blank".asJson, "browserContextId" -> browserContext.asJson))
      tab = TabRef(id, Id[TabKind](target.hcursor.get[String]("targetId").toOption.get))
      attached <- cdp.call("Target.attachToTarget", Json.obj("targetId" -> tab.tab.asJson, "flatten" -> true.asJson))
      session = attached.hcursor.get[String]("sessionId").toOption.get
      remove = cdp.listen { event =>
        if event.hcursor.get[String]("method").contains("Fetch.requestPaused") && event.hcursor.get[String]("sessionId").contains(session) then
          val requestId = event.hcursor.downField("params").get[String]("requestId").toOption.get
          cdp.call("Fetch.fulfillRequest", Json.obj("requestId" -> requestId.asJson, "responseCode" -> 200.asJson,
            "responseHeaders" -> Json.arr(Json.obj("name" -> "Content-Type".asJson, "value" -> "text/html".asJson)),
            "body" -> dom.window.btoa("<!doctype html><title>HTPC private runtime</title>").asJson), Some(session)).failed.foreach(_ => ())
      }
      _ <- cdp.call("Fetch.enable", Json.obj("patterns" -> Json.arr(Json.obj("urlPattern" -> url.asJson, "resourceType" -> "Document".asJson))), Some(session))
      _ <- cdp.call("Page.navigate", Json.obj("url" -> url.asJson), Some(session))
      _ <- cdp.call("Runtime.evaluate", Json.obj("expression" -> "new Promise(resolve=>document.readyState==='complete'?resolve(true):addEventListener('load',()=>resolve(true),{once:true}))".asJson, "awaitPromise" -> true.asJson), Some(session))
      _ <- cdp.call("Fetch.disable", session = Some(session)).map(_ => remove())
      tree <- cdp.call("Page.getFrameTree", session = Some(session))
      world = BrowserWorld(tab, "htpc-private-runtime")
      isolated <- cdp.call("Page.createIsolatedWorld", Json.obj("frameId" -> tree.hcursor.downField("frameTree").downField("frame").get[String]("id").toOption.get.asJson,
        "worldName" -> world.name.asJson), Some(session))
      page = new RemotePage(world, cdp, session, isolated.hcursor.get[Int]("executionContextId").toOption.get)
      _ <- page.run(BrowserRuntime.helpers)
    yield page
    pages += origin -> created
    created.failed.foreach(_ => { pages -= origin; close() })
    created
  }) }
  def close(): Unit =
    connection.foreach(_.foreach(_._2.close()))
    connection = None; pages = Map.empty

object BrowserRuntime:
  val helpers = """globalThis.http = async (path, fields=null, bearer=null) => {
    const headers={};if(fields)headers['Content-Type']='application/x-www-form-urlencoded';if(bearer)headers.Authorization='Bearer '+bearer;
    const r=await fetch(path,{method:fields?'POST':'GET',headers,body:fields?new URLSearchParams(fields).toString():undefined,credentials:'include',signal:AbortSignal.timeout(25000)});
    const json=await r.json();return {status:r.status,result:Number(r.headers.get('x-eresult')||1),json};
  };globalThis.jwt = token=>JSON.parse(atob(token.split('.')[1].replace(/-/g,'+').replace(/_/g,'/')));true"""
