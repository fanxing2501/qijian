package htpc.server

import cats.effect.IO
import htpc.protocol.*
import io.circe.syntax.*
import java.net.URI
import munit.CatsEffectSuite

class HostServicesSuite extends CatsEffectSuite:
  test("external CDP targets cannot change the configured loopback authority") {
    val base = URI.create("http://127.0.0.1:8080")
    assert(HostServices.validSocket(base, URI.create("ws://127.0.0.1:8080/devtools/page/ABC")))
    Vector("ws://127.0.0.1:8081/devtools/page/ABC", "ws://example.com:8080/x", "ws://user@127.0.0.1:8080/x", "wss://127.0.0.1:8080/x")
      .foreach(s => assert(!HostServices.validSocket(base, URI.create(s))))
    Vector("https://127.0.0.1:8080", "http://localhost:8080", "http://10.0.0.1:8080", "http://127.0.0.1")
      .foreach(s => assert(!HostServices.validEndpoint(URI.create(s))))
  }
  test("service names cannot inject a unit option or a socket route") {
    Vector("../steam", "--user", "a/b", "a?b", "a\nb", "").foreach(s => assert(!HostServices.validName(s)))
    assert(HostServices.validName("steam"))
  }
  test("unconfigured host service is a typed unavailable error") {
    new HostServices(None).handle(HostOp.Start("example")).map { value =>
      assertEquals(value.left.map(_.code), Left(ErrorCode.Unavailable))
    }
  }
  test("host commands preserve their request-specific result codecs") {
    val request = HostOp.Targets("example")
    val packed = Protocol.decode(Module.Host, request.wire("x")).toOption.get
    assertEquals(packed.request.args, request.args)
    val values = Vector(HostTarget("A", "Application", "about:blank", "page"))
    assertEquals(request.decode(RpcResponse("x", "result", values.asJson)), Right(values))
    assert(request.decode(RpcResponse("x", "result", Done().asJson)).isLeft)
  }
