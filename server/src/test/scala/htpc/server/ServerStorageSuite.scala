package htpc.server

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import htpc.protocol.*
import io.circe.Json
import io.circe.syntax.*
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import munit.CatsEffectSuite
import scala.compiletime.testing.typeCheckErrors

class ServerStorageSuite extends CatsEffectSuite:
  private def directory = Resource.make(IO.blocking(Files.createTempDirectory(Path.of("/tmp/opencode"), "server-storage-")))(Resources.deleteTree)
  private val key = StorageKey("test-app", "account")
  test("typed KV preserves absent versus JSON null, disk state and owner-only permissions") {
    directory.use { dir => for
      storage <- ServerStorage.create(dir)
      absent <- storage.get(key)
      stamp <- storage.write(key, Some(Json.Null), Some(absent.revision))
      restarted <- ServerStorage.create(dir)
      result <- restarted.get(key)
      permissions <- IO.blocking(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("test-app/account.json"))))
    yield {
      assertEquals(absent, StorageEntry(None, "0"))
      assert(stamp.present)
      assertEquals(result.value, Some(Json.Null))
      assertEquals(permissions, "rw-------")
    }}
  }
  test("compare-and-set serializes competing refreshes, tombstones prevent logout resurrection") {
    directory.use { dir => for
      storage <- ServerStorage.create(dir)
      results <- (storage.write(key, Some("first".asJson), Some("0")).attempt,
        storage.write(key, Some("second".asJson), Some("0")).attempt).parTupled
      value <- storage.get(key)
      deleted <- storage.write(key, None, None)
      stale <- storage.write(key, Some("stale-token".asJson), Some(value.revision)).attempt
      empty <- storage.get(key)
    yield {
      assertEquals(Vector(results._1, results._2).count(_.isRight), 1)
      assert(stale.isLeft)
      assert(!deleted.present)
      assertEquals(empty.value, None)
      assert(empty.revision != "0")
    }}
  }
  test("namespaces are isolated, paths and oversized entries are rejected") {
    directory.use { dir => for
      storage <- ServerStorage.create(dir)
      _ <- storage.write(key, Some("private".asJson), None)
      other <- storage.get(key.copy(namespace = "another-app"))
      traversal <- storage.get(key.copy(key = "../../escape")).attempt
      oversized <- storage.write(key, Some(("x" * (1024 * 1024 + 1)).asJson), None).attempt
    yield {
      assertEquals(other.value, None)
      assert(traversal.isLeft)
      assert(oversized.isLeft)
    }}
  }
  test("GADT distinguishes values, stamps and browser sockets without caller result tags") {
    assert(typeCheckErrors("""
      import htpc.protocol.*
      val wrong: Request[StorageError, StorageEntry] = StorageOp.Delete(StorageKey("a", "b"))
    """).nonEmpty)
    val op = StorageOp.ReadInto(key, BrowserWorld(TabRef(Id("browser"), Id("tab")), "private-world"), "grant")
    assertEquals(Protocol.decode(Module.Storage, op.wire("x")).toOption.get.request.args, op.args)
    assert(Protocol.decode(Module.Storage, RpcRequest("x", "device.start")).isLeft)
  }
