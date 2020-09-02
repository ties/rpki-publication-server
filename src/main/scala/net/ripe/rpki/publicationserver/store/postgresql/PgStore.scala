package net.ripe.rpki.publicationserver.store.postresql

import java.net.URI

import net.ripe.rpki.publicationserver.Binaries.Bytes
import net.ripe.rpki.publicationserver._
import net.ripe.rpki.publicationserver.metrics.Metrics
import net.ripe.rpki.publicationserver.model.ClientId
import net.ripe.rpki.publicationserver.store.ObjectStore
import org.json4s._
import org.json4s.native.JsonMethods._
import scalikejdbc.{ConnectionPool, ConnectionPoolSettings, DB, DBSession, IsolationLevel, NoExtractor, SQL, scalikejdbcSQLInterpolationImplicitDef}


class RollbackException(val error: BaseError) extends Exception

class PgStore(val pgConfig: PgConfig) extends Hashing with Logging {

  def inTx[T](f: DBSession => T): T = {
    DB(ConnectionPool.borrow())
      .isolationLevel(IsolationLevel.RepeatableRead)
      .localTx(f)
  }

  def clear(): Unit = DB.localTx { implicit session =>
    sql"DELETE FROM objects".update.apply()
    sql"DELETE FROM object_log".update.apply()
    sql"DELETE FROM versions".update.apply()
  }

  def getState: ObjectStore.State = DB.localTx { implicit session =>
    sql"SELECT * FROM current_state"
      .map { rs =>
        val hash = Hash(rs.string(1))
        val uri = URI.create(rs.string(2))
        val clientId = ClientId(rs.string(3))
        val bytes = Bytes.fromStream(rs.binaryStream(4))
        uri -> (bytes, hash, clientId)
      }
      .list
      .apply
      .toMap
  }

  def getLog = DB.localTx { implicit session =>
    sql"""SELECT operation, url, old_hash, content
         FROM object_log
         ORDER BY id ASC"""
      .map { rs =>
        val operation = rs.string(1)
        val uri = URI.create(rs.string(2))
        val hash = rs.stringOpt(3).map(Hash)
        val bytes = Bytes.fromStream(rs.binaryStream(4))
        (operation, uri, hash, bytes)
      }
      .list
      .apply
  }

  def readState(f: (URI, Hash, Bytes) => Unit)(implicit session: DBSession) = {
    sql"SELECT url, hash, content FROM current_state"
      .foreach { rs =>
        val uri = URI.create(rs.string(1))
        val hash = Hash(rs.string(2))
        val bytes = Bytes.fromStream(rs.binaryStream(3))
        f(uri, hash, bytes)
      }
  }

  def readDelta(sessionId: String, serial: Long)(f: (String, URI, Option[Hash], Option[Bytes]) => Unit)(implicit session: DBSession) = {
    sql"""SELECT operation, url, old_hash, content
         FROM deltas
         WHERE session_id = ${sessionId} AND serial = ${serial}"""
      .foreach { rs =>
        val operation = rs.string(1)
        val uri = URI.create(rs.string(2))
        val oldHash = rs.stringOpt(3).map(Hash)
        val bytes = rs.binaryStreamOpt(4).map(Bytes.fromStream)
        f(operation, uri, oldHash, bytes)
      }
  }

  def getCurrentSessionInfo(implicit session: DBSession) = {
    sql"""SELECT session_id, serial
          FROM versions
          ORDER BY id DESC LIMIT 1"""
      .map(rs => (rs.string(1), rs.long(2)))
      .single()
      .apply()
  }

  def getReasonableDeltas(sessionId: String)(implicit session: DBSession) = {
    sql"""SELECT serial, delta_hash
          FROM reasonable_deltas
          WHERE session_id = ${sessionId}
          ORDER BY serial DESC LIMIT 1"""
      .map { rs =>
        (rs.long(1), Hash(rs.string(2)))
      }
      .list()
      .apply()
  }

  def updateSnapshotInfo(sessionId: String, serial: Long, hash: Hash, size: Long)(implicit session: DBSession): Unit = {
    sql"""UPDATE versions SET
            snapshot_hash = ${hash.hash},
            snapshot_size = ${size}
          WHERE session_id = ${sessionId} AND serial = ${serial}"""
      .execute()
      .apply()
  }

  def updateDeltaInfo(sessionId: String, serial: Long, hash: Hash, size: Long)(implicit session: DBSession): Unit = {
    sql"""UPDATE versions SET
            delta_hash = ${hash.hash},
            delta_size = ${size}
          WHERE session_id = ${sessionId} AND serial = ${serial}"""
      .execute()
      .apply()
  }

  implicit val formats = org.json4s.DefaultFormats

  def applyChanges(changeSet: QueryMessage, clientId: ClientId)(implicit metrics: Metrics): Unit = {

    def executeSql(sql: SQL[Nothing, NoExtractor], onSuccess: => Unit, onFailure: => Unit)(implicit session: DBSession): Unit = {
      val r = sql.map(_.string(1)).single().apply()
      r match {
        case None =>
          onSuccess
        case Some(json) =>
          onFailure
          val error = parse(json).extract[BaseError]
          throw new RollbackException(error)
      }
    }

    inTx { implicit session =>
      // Apply all modification while holding a lock on the client ID
      // (which most often is the CA owning the objects)
      sql"SELECT acquire_client_id_lock(${clientId.value})".execute().apply()

      changeSet.pdus.foreach {
        case WithdrawQ(uri, _, hash) =>
          executeSql(
            sql"SELECT delete_object(${uri.toString}, ${hash}, ${clientId.value})",
            metrics.withdrawnObject(),
            metrics.failedToDelete())
        case PublishQ(uri, _, None, Bytes(bytes)) =>
          executeSql(
              sql"SELECT create_object(${bytes},  ${uri.toString}, ${clientId.value})",
            metrics.publishedObject(),
            metrics.failedToAdd())
        case PublishQ(uri, _, Some(oldHash), Bytes(bytes)) =>
          executeSql(
            sql"SELECT replace_object(${bytes}, ${oldHash}, ${uri.toString}, ${clientId.value})",
            {
              metrics.withdrawnObject();
              metrics.publishedObject()
            },
            metrics.failedToReplace())
      }
    }
  }

  def lockVersions(implicit session: DBSession) = {
    sql"SELECT lock_versions()".execute().apply()
  }

  def freezeVersion(implicit session: DBSession) = {
    sql"SELECT session_id, serial FROM freeze_version()"
      .map(rs => (rs.string(1), rs.long(2)))
      .single()
      .apply()
      .get
  }

  def check() = {
    val z = inTx { implicit session =>
      sql"SELECT 1".map(_.int(1)).single().apply()
    }
    z match {
      case None =>
        throw new Exception("Something is really wrong with the database")
      case Some(i) if i != 1 =>
        throw new Exception(s"Something is really wrong with the database, returned $i instead of 1")
      case _ => ()
    }
  }

  def list(clientId: ClientId) = inTx { implicit session =>
    sql"""SELECT url, hash
           FROM current_state
           WHERE client_id = ${clientId.value}"""
      .map(rs => (rs.string(1), rs.string(2)))
      .list
      .apply
  }
}

object PgStore {
  var pgStore : PgStore = _

  def get(pgConfig: PgConfig): PgStore = synchronized {
    if (pgStore == null) {
      val settings = ConnectionPoolSettings(
        initialSize = 5,
        maxSize = 20,
        connectionTimeoutMillis = 3000L,
        validationQuery = "select 1")

      ConnectionPool.singleton(
        pgConfig.url, pgConfig.user, pgConfig.password, settings)

      pgStore = new PgStore(pgConfig)
    }
    pgStore
  }

}



