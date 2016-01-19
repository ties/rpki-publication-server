package net.ripe.rpki.publicationserver.messaging

import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.{Date, UUID}

import akka.actor.{Actor, ActorRef, Props}
import com.softwaremill.macwire.MacwireMacros._
import net.ripe.rpki.publicationserver._
import net.ripe.rpki.publicationserver.messaging.Messages._
import net.ripe.rpki.publicationserver.model.{Delta, Notification, ServerState, Snapshot}
import net.ripe.rpki.publicationserver.store.ObjectStore
import net.ripe.rpki.publicationserver.store.fs.{RrdpRepositoryWriter, RsyncRepositoryWriter}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success}

object Flusher {
  def props = Props(new Flusher)
}

class Flusher extends Actor with Config with Logging {

  import context._

  private lazy val rrdpWriter = wire[RrdpRepositoryWriter]
  private lazy val rsyncWriter = wire[RsyncRepositoryWriter]

  private type DeltaMap = Map[Long, (Long, Hash, Long, Instant)]

  private var deltas: DeltaMap = Map()
  private var deltasToDelete: Seq[(Long, Instant)] = Seq()

  private val sessionId = UUID.randomUUID()

  private var serial = 1L

  private var dataCleaner: ActorRef = _

  override def preStart() = {
    dataCleaner = context.actorOf(Cleaner.props)

    // TODO Implement safer removal if needed (create new session
    // TODO before removing all the others or something)
    rrdpWriter.cleanRepository(conf.rrdpRepositoryPath)
    rrdpWriter.createSession(conf.rrdpRepositoryPath, sessionId, serial)
  }

  override def receive: Receive = {
    case BatchMessage(messages, state) =>
      flush(messages, state)
      serial += 1
  }


  def flush(messages: Seq[QueryMessage], state: ObjectStore.State) = {
    val pdus = messages.flatMap(_.pdus)
    val delta = Delta(sessionId, serial, pdus)
    deltas += serial -> (serial, delta.contentHash, delta.binarySize, Instant.now())

    val serverState = ServerState(sessionId, serial)
    val snapshotPdus = state.map { e =>
      val (uri, (base64, _, _)) = e
      (base64, uri)
    }.toSeq

    val snapshot = Snapshot(serverState, snapshotPdus)
    val (deltasToPublish, deltasToDelete) = separateDeltas(deltas, snapshot.binarySize)

    val deltaDefs = deltasToPublish.map { e =>
      val (serial, (_, hash, _, _)) = e
      (serial, hash)
    }.toSeq

    val notification = Notification.create2(snapshot, serverState, deltaDefs)

    val rrdp = Future {
      logger.debug(s"Writing delta $serial to rsync filesystem")
      rrdpWriter.writeDelta(conf.rrdpRepositoryPath, delta)
    }
    val rsync = Future {
      logger.debug(s"Writing delta $serial to RRDP filesystem")
      rsyncWriter.writeDelta(delta)
    }

    waitFor(rrdp).flatMap { _ =>
      waitFor(rsync).flatMap { _ =>
        val result = rrdpWriter.writeNewState(conf.rrdpRepositoryPath, serverState, notification, snapshot)
        deltas = deltasToPublish
        result
      }.recoverWith {
        case e: Exception =>
          logger.error(s"Could not write delta $serial to RRDP repo: ", e)
          Failure(e)
      }
    }.recoverWith {
      case e: Exception =>
        logger.error(s"Could not write delta $serial to rsync repo: ", e)
        Failure(e)
    } match {
      case Success(timestampOption) =>
        timestampOption.foreach(scheduleSnapshotCleanup(serial))
        val now = Instant.now()
        scheduleDeltaCleanups(deltasToDelete.keys)
      case Failure(e) =>
        logger.error("Could not write notification.xml to filesystem: " + e.getMessage, e)
    }

  }

  private def waitFor[T](f: Future[T]) = Await.result(f, 10.minutes)

  def separateDeltas(deltas: Map[Long, (Long, Hash, Long, Instant)], snapshotSize: Long) : (DeltaMap, DeltaMap) = {
    if (deltas.isEmpty)
      (deltas, Map())
    else {
      val deltasNewestFirst = deltas.values.toSeq.sortBy(-_._1)
      var accDeltaSize = deltasNewestFirst.head._3
      val thresholdDelta = deltasNewestFirst.tail.find { d =>
        accDeltaSize += d._3
        accDeltaSize > snapshotSize
      }

      thresholdDelta match {
        case Some((s, _, _, _)) =>
          val p = deltas.partition(_._1 < s)
          logger.info(s"Deltas with serials smaller than $s will be removed after $afterRetainPeriod, ${p._2.keys.mkString}")
          p
        case None => (deltas, Map())
      }
    }
  }


  def snapshotCleanInterval = {
    val i = conf.unpublishedFileRetainPeriod / 10
    if (i < 1.second) 1.second else i
  }

  def afterRetainPeriod = new Date(System.currentTimeMillis() + conf.unpublishedFileRetainPeriod.toMillis)

  var snapshotFSCleanupScheduled = false

  def scheduleSnapshotCleanup(currentSerial: Long)(timestamp: FileTime) = {
    if (!snapshotFSCleanupScheduled) {
      system.scheduler.scheduleOnce(snapshotCleanInterval, new Runnable() {
        override def run() = {
          val command = CleanUpSnapshot(timestamp, currentSerial)
          dataCleaner ! command
          logger.debug(s"$command has been sent")
          snapshotFSCleanupScheduled = false
        }
      })
      snapshotFSCleanupScheduled = true
    }
  }

  def scheduleDeltaCleanups(deltasToDelete: Iterable[Long]) = {
    system.scheduler.scheduleOnce(conf.unpublishedFileRetainPeriod, new Runnable() {
      override def run() = {
        val command = CleanUpDeltas(sessionId, deltasToDelete)
        dataCleaner ! command
        logger.debug(s"$command has been sent")
      }
    })
  }

}


class Cleaner extends Actor with Config with Logging {

  private lazy val rrdpWriter = wire[RrdpRepositoryWriter]

  override def receive = {
    case CleanUpSnapshot(timestamp, serial) =>
      logger.info(s"Removing snapshots older than $timestamp and having serial number older than $serial")
      rrdpWriter.deleteSnapshotsOlderThan(conf.rrdpRepositoryPath, timestamp, serial)
    case CleanUpDeltas(sessionId, serials) =>
      logger.info(s"Removing deltas with serials: $serials")
      rrdpWriter.deleteDeltas(conf.rrdpRepositoryPath, sessionId, serials)
  }

}

object Cleaner {
  def props = Props(new Cleaner)
}