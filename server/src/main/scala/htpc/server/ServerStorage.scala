package htpc.server

import cats.effect.IO
import cats.effect.std.Semaphore
import cats.syntax.all.*
import htpc.protocol.*
import io.circe.Json
import io.circe.parser.decode
import io.circe.syntax.*
import java.nio.file.{Files, Path, StandardCopyOption, LinkOption}
import java.nio.file.attribute.PosixFilePermissions

final class ServerStorage private(root: Path, lock: Semaphore[IO]):
  private def path(key: StorageKey): IO[Path] =
    Fault.require(Vector(key.namespace, key.key).forall(_.matches("[A-Za-z0-9_-]{1,100}")), "Invalid storage namespace/key") *>
      IO.pure(root.resolve(key.namespace).resolve(key.key + ".json"))
  private def read(path: Path): IO[StorageEntry] = IO.blocking {
    if !Files.exists(path, LinkOption.NOFOLLOW_LINKS) then StorageEntry(None, "0")
    else
      if Files.isSymbolicLink(path) then throw Fault(ErrorCode.InvalidArgument, "Storage symlinks are not supported")
      decode[StorageEntry](Files.readString(path)).fold(_ => throw Fault(ErrorCode.Serialization, "Stored entry is corrupt"), identity)
  }
  def get(key: StorageKey): IO[StorageEntry] = lock.permit.use(_ => path(key).flatMap(read))
  def write(key: StorageKey, value: Option[Json], expected: Option[String]): IO[StorageStamp] = lock.permit.use { _ => for
    file <- path(key)
    _ <- Fault.require(value.forall(_.noSpaces.getBytes("UTF-8").length <= 1024 * 1024), "Stored value exceeds 1 MiB")
    previous <- read(file)
    _ <- IO.raiseWhen(expected.exists(_ != previous.revision))(Fault(ErrorCode.Conflict, "Stored value changed; reload before writing"))
    entry = StorageEntry(value, java.util.UUID.randomUUID().toString)
    _ <- IO.blocking {
      if Files.isSymbolicLink(file.getParent) then throw Fault(ErrorCode.InvalidArgument, "Storage symlinks are not supported")
      Files.createDirectories(file.getParent)
      Files.setPosixFilePermissions(file.getParent, PosixFilePermissions.fromString("rwx------"))
      val temp = Files.createTempFile(file.getParent, ".write-", ".json", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
      try
        Files.writeString(temp, entry.asJson.noSpaces)
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      finally Files.deleteIfExists(temp)
    }
  yield entry.stamp }

  def handle[A](op: StorageOp[A], browser: Chromium): IO[Either[StorageError, A]] =
    val action: IO[A] = op match
      case StorageOp.Get(key) => get(key)
      case StorageOp.Inspect(key) => get(key).map(_.stamp)
      case StorageOp.Put(key, value, expected) => write(key, Some(value), expected)
      // Keep a versioned tombstone so an in-flight refresh cannot resurrect a signed-out account.
      case StorageOp.Delete(key, expected) => write(key, None, expected)
      case StorageOp.ReadInto(key, world, binding) => get(key).flatMap(e => browser.inject(world, binding, e.value.getOrElse(Json.Null)).as(e.stamp))
      case StorageOp.WriteFrom(key, world, expression, expected) => browser.capture(world, expression).flatMap(v => write(key, Some(v), Some(expected)))
      case StorageOp.CaptureCdp(key, instance, method, params, expected) => browser.command(instance, method, params).flatMap(v => write(key, Some(v), Some(expected)))
    action.attempt.map(_.left.map {
      case f: Fault => StorageError(f.code, f.getMessage)
      case _ => StorageError(ErrorCode.Internal, "Server storage operation failed")
    })

object ServerStorage:
  def create(root: Path): IO[ServerStorage] = for
    _ <- IO.blocking {
      Files.createDirectories(root)
      Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"))
    }
    lock <- Semaphore[IO](1)
  yield new ServerStorage(root, lock)
