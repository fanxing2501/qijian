package htpc.server

import cats.effect.IO
import cats.syntax.all.*
import htpc.protocol.*
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import munit.CatsEffectSuite
import scala.compiletime.testing.typeCheckErrors

class ProtocolSuite extends CatsEffectSuite:
  test("resource IDs and GADT result types reject invalid API programs") {
    assert(typeCheckErrors("""
      import htpc.protocol.*
      val wrong = ChromiumOp.DeleteInstance(Id[WineKind]("wine-1"))
    """).nonEmpty)
    assert(typeCheckErrors("""
      import htpc.protocol.*
      val wrong: Request[ChromiumError, WineInstance] = ChromiumOp.NewInstance(ChromiumOptions())
    """).nonEmpty)
    assert(typeCheckErrors("""
      import htpc.protocol.*
      val correct: Request[ChromiumError, Vector[TabInfo]] = ChromiumOp.ListTab(Id[ChromiumKind]("browser-1"))
    """).isEmpty)
  }

  test("wire discriminator selects a matching result codec without casts") {
    val op = ChromiumOp.NewInstance(ChromiumOptions(name = "账号"))
    val packed = Protocol.decode(Module.Chromium, op.wire("test")).toOption.get
    assertEquals(packed.request.name, "instance.new")
    assertEquals(packed.request.args, op.args)
    val instance = ChromiumInstance(Id("browser-1"), "账号", None, InstanceState.Running)
    assertEquals(op.decode(RpcResponse("test", "result", instance.asJson)), Right(instance))
    assert(op.decode(RpcResponse("test", "result", Json.arr())).isLeft)
  }

  test("bad discriminator, foreign module, invalid identifiers and malformed arguments are rejected") {
    assert(Protocol.decode(Module.Wine, RpcRequest("x", "tab.list", Json.obj())).isLeft)
    assert(Protocol.decode(Module.Chromium, RpcRequest("x", "instance.delete", Json.obj("instance" -> "../../profile".asJson))).isLeft)
    assert(Protocol.decode(Module.Sway, RpcRequest("x", "window.fullscreen", Json.obj())).isLeft)
  }

  test("undefined, JSON null, typed domain failures and corrupted replies stay distinct") {
    val op = ChromiumOp.EvalJS(TabRef(Id("b"), Id("t")), "undefined", EvalOptions())
    assertEquals(op.decode(RpcResponse("x", "result", JsValue.Undefined.asJson)), Right(JsValue.Undefined))
    assertEquals(op.decode(RpcResponse("x", "result", (JsValue.Value(Json.Null): JsValue).asJson)), Right(JsValue.Value(Json.Null)))
    val error = ChromiumError(ErrorCode.Timeout, "deadline")
    assertEquals(op.decode(RpcResponse("x", "error", error.asJson)), Left(CallError.Domain(error)))
    assert(op.decode(RpcResponse("x", "surprise", Json.Null)).isLeft)
  }

  test("CHIPS cookie identity and HttpOnly survive the shared protocol") {
    val cookie = Cookie("session", "abc", ".example.test", "/login", Some(2000000000), true, true, Some("None"),
      Some(Json.obj("topLevelSite" -> "https://example.test".asJson, "hasCrossSiteAncestor" -> true.asJson)))
    assertEquals(cookie.asJson.as[Cookie], Right(cookie))
  }

  test("profile traversal and unbounded execution deadlines are rejected") {
    for
      invalid <- Resources.profile(java.nio.file.Path.of("/tmp/opencode/unused"), Some("../../escape"), "x").attempt
      duration <- Fault.timeout(Int.MaxValue).attempt
    yield
      assert(invalid.isLeft)
      assert(duration.isLeft)
  }
