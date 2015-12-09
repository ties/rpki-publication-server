package net.ripe.rpki.publicationserver

import java.io.File
import java.nio.file.{FileSystems, Files}
import java.util.UUID

import akka.testkit.TestActorRef
import net.ripe.rpki.publicationserver.model.ServerState
import net.ripe.rpki.publicationserver.store.fs.FSWriterActor
import org.apache.commons.io.FileUtils
import spray.testkit.ScalatestRouteTest

class RsyncTest extends PublicationServerBaseTest with ScalatestRouteTest {

  val rsyncDir = "/tmp/a"

  val config = AppConfig

  def actorRefFactory = system

  trait Context {
    def actorRefFactory = system
  }

  def publicationService = {
    val service = new PublicationService with Context
    service.init(fsWriterActor)
    service
  }

  def fsWriterActor = TestActorRef[FSWriterActor]

  before {
    val tmpDir = new File(rsyncDir)
    if (tmpDir.exists()) {
      FileUtils.deleteDirectory(tmpDir)
    }
  }

  test("should publish the contents in the publication request to the correct rsync folder") {
    POST("/?clientId=1234", getFile("/publish.xml").mkString) ~> publicationService.publicationRoutes ~> check {
      response.status.isSuccess should be(true)

      // The publish.xml request contains a certificate with uri rsync://wombat.example/Alice/blCrcCp9ltyPDNzYKPfxc.cer
      // The reference.conf file in test/resources contains a mapping from the prefix rsync://wombat.example to the filesystem location /tmp/a
      // So the filesystem location where the rsyncRepositoryWriter should publish the file is /tmp/a + /online + /Alice/blCrcCp9ltyPDNzYKPfxc.cer

      Files.exists(FileSystems.getDefault.getPath(s"$rsyncDir/online/Alice/blCrcCp9ltyPDNzYKPfxc.cer")) should be(true)
    }
  }

  test("should write the snapshot and delta's from the db to the filesystem on init") {
    val sessionId = UUID.randomUUID()
    val newServerState = ServerState(sessionId, 123L)
    fsWriterActor.underlyingActor.initFSContent(newServerState)

    // The tmp/a directory is removed in the 'before' method, but the database still contains the object published by the previous test.
    // So initFSContent should find the object in the database and write it to rsync

    Files.exists(FileSystems.getDefault.getPath(s"$rsyncDir/online/Alice/blCrcCp9ltyPDNzYKPfxc.cer")) should be(true)
  }
}