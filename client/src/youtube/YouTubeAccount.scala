package htpc.client.youtube

import htpc.client.ApiClient
import htpc.client.accounts.*
import htpc.protocol.*
import io.circe.Json
import io.circe.syntax.*
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

final class YouTubeAccount(client: ApiClient) extends BrowserAccount(client, "youtube"):
  private val clientKey = StorageKey(namespace, "client")
  private case class Flow(code: DeviceAuthorization, revision: String)
  private var flows = Map.empty[DeviceFlowId, Flow]
  private def key(id: DeviceFlowId) = StorageKey(namespace, "flow-" + id.value)
  protected def pendingKeys: Vector[StorageKey] = flows.keys.toVector.map(key)
  private val clientScript = """if(!_client)throw Error('请配置 serverStorage youtube/client 的 Google TV OAuth 客户端。');
    _client=_client.installed||_client;_client={clientId:_client.clientId||_client.client_id,clientSecret:_client.clientSecret||_client.client_secret};
    if(!_client.clientId||!_client.clientSecret)throw Error('Google TV OAuth 客户端配置不完整。');"""
  private val grantScript = """const j=_reply.json;if(!j.access_token||!(j.refresh_token||_grant?.refreshToken)||!(j.expires_in>0))throw Error('Google 未返回完整令牌。');
    _grant={clientId:_client.clientId,accessToken:j.access_token,refreshToken:j.refresh_token||_grant.refreshToken,expiresAt:Date.now()+j.expires_in*1000};"""

  private def start(): Future[DeviceAuthorization] = for
    page <- browser.page("https://oauth2.googleapis.com")
    _ <- load(page, clientKey, "_client")
    grant <- storage.inspect(grantKey)
    id: DeviceFlowId = Id(newId)
    stamp <- storage.inspect(key(id))
    public <- execute(page, clientScript + """
      const r=await http('/device/code',{client_id:_client.clientId,scope:'https://www.googleapis.com/auth/youtube.readonly'});
      if(r.status!==200)throw Error('Google 设备授权失败（HTTP '+r.status+'）');
      const j=r.json,url=new URL(j.verification_url||j.verification_uri);
      if(url.protocol!=='https:'||!['www.google.com','google.com','accounts.google.com'].includes(url.hostname)||!j.device_code||!j.user_code||!(j.expires_in>0))throw Error('Google 返回了无效的设备码。');
      const interval=Math.max(5,Math.min(300,j.interval||5))*1000;
      _flow={code:j.device_code,expiresAt:Date.now()+j.expires_in*1000,intervalMs:interval,nextPollAt:Date.now()+interval};
      return {userCode:j.user_code,url:url.href,expiresAt:_flow.expiresAt,intervalMs:interval};""")
    _ <- save(page, key(id), "_flow", stamp)
    _ <- wipe(page)
    url = public.hcursor.get[String]("url").toOption.get
    image <- qr(url)
    code = DeviceAuthorization(id, public.hcursor.get[String]("userCode").toOption.get, url, image,
      public.hcursor.get[Long]("expiresAt").toOption.get, public.hcursor.get[Int]("intervalMs").toOption.get)
    _ = flows += id -> Flow(code, grant.revision)
  yield code

  private def cancel(id: DeviceFlowId): Future[Done] =
    flows -= id
    storage.delete(key(id)).map(_ => Done())

  private def poll(id: DeviceFlowId): Future[AuthorizationProgress] = flows.get(id) match
    case None => Future.successful(AuthorizationProgress.Expired)
    case Some(flow) if now >= flow.code.expiresAt => cancel(id).map(_ => AuthorizationProgress.Expired)
    case Some(flow) => (for
      page <- browser.page("https://oauth2.googleapis.com")
      stamp <- load(page, key(id), "_flow")
      _ <- load(page, clientKey, "_client")
      result <- execute(page, clientScript + """
        if(!_flow||Date.now()>=_flow.expiresAt)return {kind:'expired'};
        if(Date.now()<_flow.nextPollAt)return {kind:'pending',interval:_flow.nextPollAt-Date.now()};
        _flow.nextPollAt=Date.now()+_flow.intervalMs;
        _reply=await http('/token',{client_id:_client.clientId,client_secret:_client.clientSecret,device_code:_flow.code,grant_type:'urn:ietf:params:oauth:grant-type:device_code'});
        if(_reply.status===200){""" + grantScript + """return {kind:'authorized'};}
        const e=_reply.json.error;
        if(e==='slow_down'){_flow.intervalMs+=5000;_flow.nextPollAt=Date.now()+_flow.intervalMs;}
        if(e==='slow_down'||e==='authorization_pending')return {kind:'pending',interval:_flow.intervalMs};
        if(e==='expired_token'||e==='invalid_grant')return {kind:'expired'};
        return {kind:'denied'};""")
      progress <- result.hcursor.get[String]("kind").toOption.get match
        case "authorized" => storage.writeFrom(grantKey, page, "_grant", flow.revision).flatMap(_ => cancel(id)).map(_ => AuthorizationProgress.Authorized)
        case "pending" => save(page, key(id), "_flow", stamp).map(_ => AuthorizationProgress.Pending(result.hcursor.get[Int]("interval").toOption.get))
        case "expired" => cancel(id).map(_ => AuthorizationProgress.Expired)
        case _ => cancel(id).map(_ => AuthorizationProgress.Denied("Google 已拒绝此次授权，请检查账号权限后重试。"))
      _ <- wipe(page)
    yield progress)

  private def access(force: Boolean): Future[Unit] = for
    page <- browser.page("https://oauth2.googleapis.com")
    stamp <- load(page, grantKey, "_grant")
    _ <- load(page, clientKey, "_client")
    result <- execute(page, clientScript + s"""
      if(!_grant||_grant.clientId!==_client.clientId)throw Error('请先用手机连接 YouTube 账号。');
      if(!${force}&&_grant.expiresAt>Date.now()+60000)return 'current';
      _reply=await http('/token',{client_id:_client.clientId,client_secret:_client.clientSecret,refresh_token:_grant.refreshToken,grant_type:'refresh_token'});
      if(_reply.json.error==='invalid_grant')return 'invalid';
      if(_reply.status!==200)throw Error('Google 令牌续期失败（HTTP '+_reply.status+'）');
      """ + grantScript + "return 'updated';")
    _ <- result.asString match
      case Some("updated") => save(page, grantKey, "_grant", stamp).map(_ => ())
      case Some("invalid") => htpc.client.infra.Rpc(api, StorageOp.Delete(grantKey, Some(stamp.revision))).flatMap(_ => Future.failed(RuntimeException("Google 授权已过期，请重新扫码。")))
      case _ => Future.successful(())
    _ <- wipe(page)
  yield ()

  private def get(path: String, query: Map[String, String]): Future[Json] =
    def fetch(): Future[Json] = for
      page <- browser.page("https://www.googleapis.com")
      _ <- load(page, grantKey, "_grant")
      result <- execute(page, s"""if(!_grant)throw Error('请重新登录 YouTube。');
        return await http('/youtube/v3/'+${path.asJson.noSpaces}+'?'+new URLSearchParams(${query.asJson.noSpaces}),null,_grant.accessToken);""")
      _ <- wipe(page)
    yield result
    for
      _ <- access(false)
      first <- fetch()
      reply <- if first.hcursor.get[Int]("status").contains(401) then access(true).flatMap(_ => fetch()) else Future.successful(first)
      json <- if reply.hcursor.get[Int]("status").contains(200) then Future.successful(reply.hcursor.downField("json").focus.get)
        else Future.failed(RuntimeException("YouTube API 请求失败，请检查 API 启用状态、配额和账号授权。"))
    yield json

  def request[A](op: AuthOp[A]): Future[A] = serialized[A] { op match
    case AuthOp.Status(_) => (for
      client <- storage.inspect(clientKey)
      grant <- storage.inspect(grantKey)
    yield AccountStatus(client.present, client.present && grant.present, if client.present then "设备码授权" else "尚未配置 serverStorage youtube/client") )
    case AuthOp.Start(_) => start()
    case AuthOp.Poll(id) => poll(id)
    case AuthOp.Cancel(id) => cancel(id)
    case AuthOp.SignOut(_) => Future.sequence(flows.keys.toVector.map(cancel)).flatMap(_ => storage.delete(grantKey)).map(_ => { close(); Done() })
    case AuthOp.Get(_, path, query) => get(path, query)
  }
