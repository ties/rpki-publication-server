package net.ripe.rpki.publicationserver.store.fs

import java.nio.file._
import java.nio.file.attribute.FileTime

import net.ripe.rpki.publicationserver._
import net.ripe.rpki.publicationserver.model.{Delta, Notification, ServerState, Snapshot}

import scala.util.{Failure, Try}

class RrdpRepositoryWriter extends Logging {

  def writeNewState(rootDir: String, serverState: ServerState, newNotification: Notification, snapshot: Snapshot): Try[Option[FileTime]] =
    Try {
      writeSnapshot(rootDir, serverState, snapshot)
      writeNotification(rootDir, newNotification)
    }.recoverWith { case e: Exception =>
      logger.error("An error occurred, removing snapshot: ", e)
      deleteSnapshot(rootDir, serverState)
      Failure(e)
    }

  private val snapshotFilename: String = "snapshot.xml"

  def writeSnapshot(rootDir: String, serverState: ServerState, snapshot: Snapshot) = {
    val ServerState(sessionId, serial) = serverState
    val stateDir = getStateDir(rootDir, sessionId.toString, serial)
    writeFile(snapshot.serialized, stateDir.resolve(snapshotFilename))
  }

  def writeDelta(rootDir: String, delta: Delta) = Try {
    val stateDir = getStateDir(rootDir, delta.sessionId.toString, delta.serial)
    writeFile(delta.serialize.mkString, stateDir.resolve("delta.xml"))
  }

  def writeNotification(rootDir: String, notification: Notification): Option[FileTime] = {
    val root = getRootFolder(rootDir)

    val tmpFile = Files.createTempFile(root, "notification.", ".xml")
    try {
      writeFile(notification.serialized, tmpFile)
      val target = root.resolve("notification.xml")
      val previousNotificationTimestamp = Try(Files.getLastModifiedTime(target)).toOption
      Files.move(tmpFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      previousNotificationTimestamp
    } finally {
      Files.deleteIfExists(tmpFile)
    }
  }

  private def writeFile(content: String, path: Path) =
    Files.write(path, content.getBytes("UTF-8"))

  private def getRootFolder(rootDir: String): Path =
    Files.createDirectories(Paths.get(rootDir))

  private def getStateDir(rootDir: String, sessionId: String, serial: Long): Path =
    Files.createDirectories(Paths.get(rootDir, sessionId, String.valueOf(serial)))

  def deleteSessionFile(rootDir: String, serverState: ServerState, name: String) = {
    val ServerState(sessionId, serial) = serverState
    Files.deleteIfExists(Paths.get(rootDir, sessionId.toString, serial.toString, name))
  }

  def deleteSnapshotsOlderThan(rootDir: String, timestamp: FileTime, latestSerial: Long): Unit = {
    Files.walkFileTree(Paths.get(rootDir), new RemovingFileVisitor(timestamp, Paths.get(snapshotFilename), latestSerial))
  }

  def deleteSnapshot(rootDir: String, serverState: ServerState) = deleteSessionFile(rootDir, serverState, snapshotFilename)

  def deleteDelta(rootDir: String, serverState: ServerState) = deleteSessionFile(rootDir, serverState, "delta.xml")

  def deleteNotification(rootDir: String) =
    Files.deleteIfExists(Paths.get(rootDir, "notification.xml"))

  def deleteDeltas(rootDir: String, deltas: Iterable[Delta]) =
    deltas.foreach { d =>
      deleteDelta(rootDir, ServerState(d.sessionId, d.serial))
    }

}