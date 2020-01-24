package net.ripe.rpki.publicationserver.store.fs

import java.io.{ByteArrayInputStream, InputStream}
import java.net.URI
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path, Paths, StandardCopyOption}

import net.ripe.rpki.publicationserver
import net.ripe.rpki.publicationserver._
import net.ripe.rpki.publicationserver.store.ObjectStore
import org.apache.commons.io.FileUtils

import scala.collection.JavaConversions._

case class RsyncFsLocation(base: Path, relative: Path)

class RsyncRepositoryWriter(conf: AppConfig) extends Logging {

  val directoryPermissions = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(conf.rsyncDirectoryPermissions))
  val filePermissions = PosixFilePermissions.fromString(conf.rsyncFilePermissions)

  val tempDirPrefix = "temp-"

  logger.info(s"Using following URL mapping:\n${conf.rsyncRepositoryMapping}")

  def writeSnapshot(state: ObjectStore.State): Unit = {
    groupByBaseDir(state).foreach { case (baseDir, objects) =>
      val tempRepoDir = createTempRepoDir(baseDir)
      try {
        objects.foreach { case (base64, rsyncFsLocation) =>
          writeObjectUnderDir(base64, tempRepoDir, rsyncFsLocation.relative)
        }
        promoteStagingToOnline(tempRepoDir)
      } finally {
        FileUtils.deleteDirectory(tempRepoDir.toFile)
      }
    }
  }

  def updateRepo(message: QueryMessage): Unit = {
    message.pdus.foreach {
      case PublishQ(uri, _, _, base64) =>
        writeFile(uri, base64)
      case WithdrawQ(uri, _, _) =>
        removeFile(uri)
      case unknown =>
        throw new UnsupportedOperationException(s"Unknown PDU in ValidatedMessage: $unknown")
    }
  }

  private def groupByBaseDir(state: ObjectStore.State): Map[Path, Seq[(publicationserver.Base64, RsyncFsLocation)]] = {
    state.toSeq.view.map {
      case (uri, (base64, _, _)) => (base64, resolvePath(uri))
    }.groupBy(_._2.base)
  }

  private def writeObjectUnderDir(base64: Base64, baseDir: Path, relative: Path): Unit = {
    val file: Path = baseDir.resolve(relative)
    createParentDirectories(file)
    writeBase64ToFile(base64, file)
  }

  private def promoteStagingToOnline(tempRepoDir: Path): Unit = {
    val target: Path = tempRepoDir.getParent.resolveSibling(conf.rsyncRepositoryOnlineDirName)
    FileUtils.deleteDirectory(target.toFile)
    Files.move(tempRepoDir, target, StandardCopyOption.ATOMIC_MOVE)
    logger.info(s"Created new repo layout at $target")
  }

  private def resolvePath(uri: URI): RsyncFsLocation = {
    conf.rsyncRepositoryMapping.collectFirst {
      case (rootUri, baseDir) if !rootUri.relativize(uri).isAbsolute =>
        RsyncFsLocation(baseDir, Paths.get(rootUri.relativize(uri).toString))
    } match {
    case Some(rsyncFsLocation) => rsyncFsLocation
    case None => throw new IllegalArgumentException(s"Unable to map URI to filesystem location: $uri")
    }
  }

  private def writeFile(uri: URI, base64: Base64): Unit = {
    val fsLocation = resolvePath(uri)

    val stagingDir: Path = stagingDirFor(fsLocation.base)
    Files.createDirectories(stagingDir)
    val tempFile: Path = Files.createTempFile(stagingDir, fsLocation.relative.getFileName.toString, ".tmp")
    writeBase64ToFile(base64, tempFile)

    val targetFile: Path = onlineFileFor(fsLocation)
    createParentDirectories(targetFile)
    Files.move(tempFile, targetFile, StandardCopyOption.ATOMIC_MOVE)
    logger.debug(s"Written $targetFile")
  }

  private def removeFile(uri: URI): Unit = {
    val target = onlineFileFor(resolvePath(uri))
    if (Files.deleteIfExists(target)) logger.info(s"Deleted $target")
    else logger.warn(s"File to delete ($target) does not exist")
  }

  private def decodedStreamFor(base64: Base64): InputStream = {
    java.util.Base64.getDecoder.wrap(new ByteArrayInputStream(base64.value.getBytes("UTF-8")))
  }

  private def writeBase64ToFile(base64: Base64, tempFile: Path): Unit = {
    Files.copy(decodedStreamFor(base64), tempFile, StandardCopyOption.REPLACE_EXISTING)
    Files.setPosixFilePermissions(tempFile, filePermissions)
  }

  private def createParentDirectories(targetFile: Path): Path = {
    Files.createDirectories(targetFile.getParent, directoryPermissions)
  }

  private def onlineFileFor(fsLocation: RsyncFsLocation): Path = {
    onlineDirFor(fsLocation.base).resolve(fsLocation.relative)
  }

  private def createTempRepoDir(baseDir: Path): Path = {
    val parentDir: Path = stagingDirFor(baseDir)
    Files.createDirectories(parentDir, directoryPermissions)
    Files.createTempDirectory(parentDir, tempDirPrefix, directoryPermissions)
  }

  def cleanUpTemporaryDirs(): Unit = {
    val rsyncDirs = conf.rsyncRepositoryMapping.values.toSet
    rsyncDirs.foreach { baseDir =>
      val parentDir: Path = stagingDirFor(baseDir)
      if (parentDir.toFile.exists()) {
        logger.info("Cleaning up temporary directories")
        Files.newDirectoryStream(parentDir).iterator().foreach { path =>
          if (path.startsWith(tempDirPrefix)) {
            FileUtils.deleteDirectory(path.toFile)
          }
        }
      }
    }
  }

  private def stagingDirFor(base: Path): Path = base.resolve(conf.rsyncRepositoryStagingDirName)
  private def onlineDirFor(base: Path): Path = base.resolve(conf.rsyncRepositoryOnlineDirName)
}
