package htpc.client.steam

import htpc.client.ApiClient
import htpc.client.accounts.*
import htpc.protocol.*
import io.circe.Json
import io.circe.syntax.*
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

final class SteamLoginSession(client: ApiClient) extends BrowserAccount(client, "steam"):
  private val origin = "https://api.steampowered.com"
  private case class Flow(code: DeviceAuthorization, revision: String)
  private var flows = Map.empty[DeviceFlowId, Flow]
  private def key(id: DeviceFlowId) = StorageKey(namespace, "flow-" + id.value)
  protected def pendingKeys: Vector[StorageKey] = flows.keys.toVector.map(key)
  private val challenge = """const u=new URL(url);if(u.protocol!=='https:'||u.hostname!=='s.team'||!u.pathname.startsWith('/q/'))throw Error('Steam 二维码地址无效。');"""
  private val tokens = """const refresh=j.refresh_token,access=j.access_token||'',claims=jwt(refresh),a=access?jwt(access):claims;
    if(!/^\d{17}$/.test(claims.sub)||a.sub!==claims.sub||!claims.exp)throw Error('Steam 返回了无效的账号令牌。');
    _grant={steamId:claims.sub,accountName:j.account_name,accessToken:access,refreshToken:refresh,expiresAt:access?a.exp*1000:0,refreshExpiresAt:claims.exp*1000};"""

  private def start(): Future[DeviceAuthorization] = for
    page <- browser.page(origin)
    grant <- storage.inspect(grantKey)
    id: DeviceFlowId = Id(newId)
    stamp <- storage.inspect(key(id))
    public <- execute(page, """
      const r=await http('/IAuthenticationService/BeginAuthSessionViaQR/v1/',{input_json:JSON.stringify({device_friendly_name:'HTPC Next',platform_type:2,website_id:'Community',device_details:{device_friendly_name:'HTPC Next',platform_type:2}})});
      if(r.status!==200||r.result!==1)throw Error('Steam 登录请求失败，请重试。');
      const j=r.json.response||r.json,url=j.challenge_url;""" + challenge + """
      if(!j.client_id||!j.request_id)throw Error('Steam 未返回登录会话。');
      const interval=Math.max(5,Math.min(60,j.interval||5))*1000;
      _flow={clientId:j.client_id,requestId:j.request_id,url,scanned:false,expiresAt:Date.now()+300000,intervalMs:interval,nextPollAt:Date.now()+interval};
      return {url,expiresAt:_flow.expiresAt,intervalMs:interval};""")
    _ <- save(page, key(id), "_flow", stamp)
    _ <- wipe(page)
    url = public.hcursor.get[String]("url").toOption.get
    image <- qr(url)
    code = DeviceAuthorization(id, "", url, image, public.hcursor.get[Long]("expiresAt").toOption.get, public.hcursor.get[Int]("intervalMs").toOption.get)
    _ = flows += id -> Flow(code, grant.revision)
  yield code

  private def cancel(id: DeviceFlowId): Future[Done] =
    flows -= id
    storage.delete(key(id)).map(_ => Done())

  private def poll(id: DeviceFlowId): Future[AuthorizationProgress] = flows.get(id) match
    case None => Future.successful(AuthorizationProgress.Expired)
    case Some(flow) if now >= flow.code.expiresAt => cancel(id).map(_ => AuthorizationProgress.Expired)
    case Some(flow) => (for
      page <- browser.page(origin)
      stamp <- load(page, key(id), "_flow")
      result <- execute(page, """
        if(!_flow||Date.now()>=_flow.expiresAt)return {kind:'expired'};
        if(Date.now()<_flow.nextPollAt)return {kind:'pending',interval:_flow.nextPollAt-Date.now()};
        const r=await http('/IAuthenticationService/PollAuthSessionStatus/v1/',{input_json:JSON.stringify({client_id:_flow.clientId,request_id:_flow.requestId})});
        if([16,27].includes(r.result))return {kind:'expired'};if([9,15].includes(r.result))return {kind:'denied'};
        if(r.status!==200||r.result!==1)throw Error('Steam 轮询失败，请重试。');
        const j=r.json.response||r.json;
        if(j.refresh_token){""" + tokens + """return {kind:'authorized'};}
        const url=j.new_challenge_url||_flow.url;""" + challenge + """
        _flow.url=url;_flow.clientId=j.new_client_id||_flow.clientId;_flow.scanned||=!!j.had_remote_interaction;
        _flow.nextPollAt=Date.now()+_flow.intervalMs;
        return {kind:'qr',url,scanned:_flow.scanned,interval:_flow.intervalMs};""")
      progress <- result.hcursor.get[String]("kind").toOption.get match
        case "authorized" => storage.writeFrom(grantKey, page, "_grant", flow.revision).flatMap(_ => cancel(id)).map(_ => AuthorizationProgress.Authorized)
        case "expired" => cancel(id).map(_ => AuthorizationProgress.Expired)
        case "denied" => cancel(id).map(_ => AuthorizationProgress.Denied("此次 Steam 登录已取消或被拒绝，请重新扫码。"))
        case "pending" => Future.successful(AuthorizationProgress.Pending(result.hcursor.get[Int]("interval").toOption.get))
        case _ => (for
          _ <- save(page, key(id), "_flow", stamp)
          url = result.hcursor.get[String]("url").toOption.get
          image <- if url == flow.code.verificationUrl then Future.successful(flow.code.qrImage) else qr(url)
          code = flow.code.copy(verificationUrl = url, qrImage = image)
          _ = flows += id -> flow.copy(code = code)
        yield AuthorizationProgress.QrPending(code, result.hcursor.get[Boolean]("scanned").getOrElse(false)))
      _ <- wipe(page)
    yield progress)

  private def renew(): Future[Unit] =
    val suffix = newId
    val transferKey = StorageKey(namespace, "transfer-" + suffix)
    val cookiesKey = StorageKey(namespace, "cookies-" + suffix)
    val action = for
      login <- browser.page("https://login.steampowered.com")
      stamp <- load(login, grantKey, "_grant")
      _ <- execute(login, """
        if(!_grant||_grant.refreshExpiresAt<=Date.now())throw Error('Steam 登录已过期，请重新扫码。');
        const r=await http('/jwt/finalizelogin',{nonce:_grant.refreshToken,sessionid:crypto.randomUUID().replace(/-/g,''),redir:'https://steamcommunity.com/login/home/?goto='});
        if(r.status!==200||r.json.error)throw Error('Steam 会话续期失败，请重新扫码。');
        _transfer=(r.json.transfer_info||[]).find(t=>{try{const u=new URL(t.url);return u.protocol==='https:'&&u.hostname==='steamcommunity.com'&&u.pathname==='/login/settoken'}catch{return false}});
        if(!_transfer)throw Error('Steam 没有返回有效的续期地址。');return true;""")
      _ <- storage.writeFrom(transferKey, login, "_transfer", "0")
      _ <- wipe(login)
      community <- browser.page("https://steamcommunity.com")
      _ <- load(community, transferKey, "_transfer")
      _ <- load(community, grantKey, "_grant")
      _ <- execute(community, """const r=await http(_transfer.url,{..._transfer.params,steamID:_grant.steamId});
        if(r.status!==200)throw Error('Steam 会话交换失败。');return true;""")
      info <- browser.info()
      _ <- storage.captureCdp(cookiesKey, info._1, "Storage.getCookies", Json.obj("browserContextId" -> info._3.asJson), "0")
      _ <- load(community, cookiesKey, "_cookies")
      _ <- execute(community, """
        const c=_cookies.cookies.find(c=>c.name==='steamLoginSecure'&&['steamcommunity.com','.steamcommunity.com'].includes(c.domain));
        if(!c)throw Error('Steam 没有返回新的会话令牌。');const value=decodeURIComponent(c.value);
        if(!value.startsWith(_grant.steamId+'||'))throw Error('Steam 续期账号不匹配。');
        const token=value.slice(_grant.steamId.length+2),claims=jwt(token);
        if(claims.sub!==_grant.steamId||claims.exp*1000<=Date.now())throw Error('Steam 返回了无效的访问令牌。');
        _grant.accessToken=token;_grant.expiresAt=claims.exp*1000;return true;""")
      _ <- save(community, grantKey, "_grant", stamp)
      _ <- wipe(community)
    yield ()
    action.transformWith(result => Future.sequence(Vector(storage.delete(transferKey), storage.delete(cookiesKey))).transformWith(_ => Future.fromTry(result)))

  private def access(force: Boolean): Future[Unit] = for
    page <- browser.page(origin)
    _ <- load(page, grantKey, "_grant")
    fresh <- execute(page, """if(!_grant||_grant.refreshExpiresAt<=Date.now())throw Error('请重新扫码登录 Steam。');return _grant.expiresAt>Date.now()+60000;""")
    _ <- wipe(page)
    _ <- if !force && fresh.asBoolean.contains(true) then Future.successful(()) else renew()
  yield ()

  private def get(path: String, query: Map[String, String]): Future[Json] =
    if path == "account" then for
      page <- browser.page(origin)
      _ <- load(page, grantKey, "_grant")
      result <- execute(page, """if(!_grant)throw Error('请登录 Steam。');return {steamid:_grant.steamId,account_name:_grant.accountName};""")
      _ <- wipe(page)
    yield result
    else
      def fetch(): Future[Json] = for
        page <- browser.page(origin)
        _ <- load(page, grantKey, "_grant")
        result <- execute(page, s"""if(!_grant)throw Error('请登录 Steam。');const query=${query.asJson.noSpaces};
          for(const k in query)if(query[k]==='true'||query[k]==='false')query[k]=query[k]==='true';query.steamid=_grant.steamId;
          const r=await http('/IPlayerService/GetOwnedGames/v1/?'+new URLSearchParams({input_json:JSON.stringify(query),access_token:_grant.accessToken}));
          return {status:r.status,result:r.result,json:r.json.response||r.json};""")
        _ <- wipe(page)
      yield result
      for
        _ <- access(false)
        first <- fetch()
        reply <- if first.hcursor.get[Int]("status").toOption.exists(Set(401, 403)) || first.hcursor.get[Int]("result").contains(15) then access(true).flatMap(_ => fetch()) else Future.successful(first)
        json <- if reply.hcursor.get[Int]("status").contains(200) && reply.hcursor.get[Int]("result").contains(1) then Future.successful(reply.hcursor.downField("json").focus.get)
          else Future.failed(RuntimeException("Steam 游戏库请求失败，请重试或重新扫码登录。"))
      yield json

  def request[A](op: AuthOp[A]): Future[A] = serialized[A] { op match
    case AuthOp.Status(_) => storage.inspect(grantKey).flatMap { stamp =>
      if !stamp.present then Future.successful(AccountStatus(true, false, "用手机 Steam App 扫码登录"))
      else for
        page <- browser.page(origin)
        _ <- load(page, grantKey, "_grant")
        result <- execute(page, "return !!_grant&&_grant.refreshExpiresAt>Date.now();")
        _ <- wipe(page)
      yield AccountStatus(true, result.asBoolean.contains(true), "用手机 Steam App 扫码登录")
    }
    case AuthOp.Start(_) => start()
    case AuthOp.Poll(id) => poll(id)
    case AuthOp.Cancel(id) => cancel(id)
    case AuthOp.SignOut(_) => Future.sequence(flows.keys.toVector.map(cancel)).flatMap(_ => storage.delete(grantKey)).map(_ => { close(); Done() })
    case AuthOp.Get(_, path, query) => get(path, query)
  }
