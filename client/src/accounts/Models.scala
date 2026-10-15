package htpc.client.accounts

import htpc.protocol.*
import io.circe.{Codec, Json}

sealed trait ProviderKind
sealed trait DeviceFlowKind
type ProviderId = Id[ProviderKind]
type DeviceFlowId = Id[DeviceFlowKind]
object Providers:
  val YouTube: ProviderId = Id("google-youtube")
  val Steam: ProviderId = Id("steam")
final case class AccountStatus(configured: Boolean, authorized: Boolean, detail: String) derives Codec.AsObject
final case class DeviceAuthorization(id: DeviceFlowId, userCode: String, verificationUrl: String,
  qrImage: String, expiresAt: Long, intervalMs: Int) derives Codec.AsObject
enum AuthorizationProgress derives Codec.AsObject:
  case Pending(retryAfterMs: Int)
  case QrPending(authorization: DeviceAuthorization, scanned: Boolean)
  case Authorized, Expired
  case Denied(message: String)

/** Local application commands, deliberately not backend RPC requests. */
sealed trait AuthOp[A]
object AuthOp:
  final case class Status(provider: ProviderId) extends AuthOp[AccountStatus]
  final case class Start(provider: ProviderId) extends AuthOp[DeviceAuthorization]
  final case class Poll(flow: DeviceFlowId) extends AuthOp[AuthorizationProgress]
  final case class Cancel(flow: DeviceFlowId) extends AuthOp[Done]
  final case class SignOut(provider: ProviderId) extends AuthOp[Done]
  final case class Get(provider: ProviderId, path: String, query: Map[String, String]) extends AuthOp[Json]
