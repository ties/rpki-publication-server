package net.ripe.rpki.publicationserver.model

import java.io.ByteArrayOutputStream
import java.net.URI

import net.ripe.rpki.publicationserver.{Base64, Hashing}

case class Snapshot(serverState: ServerState, pdus: Seq[(Base64, URI)]) extends Hashing {

  lazy val bytes = serialize
  lazy val contentHash = hash(bytes)
  lazy val binarySize = bytes.length

  def streamChars(s: String, stream: ByteArrayOutputStream): Unit = {
    s.toCharArray.foreach(c => stream.write(c))
  }

  private[model] def serialize = {
    val ServerState(sessionId, serial) = serverState
    val stream = new ByteArrayOutputStream()
    Dump.streamChars(s"""<snapshot version="1" session_id="$sessionId" serial="$serial" xmlns="http://www.ripe.net/rpki/rrdp">""", stream)
    pdus.foreach { pdu =>
      val (base64, uri) = pdu
      Dump.streamChars(s"""<publish uri="$uri">""", stream)
      Dump.streamChars(base64.value, stream)
      Dump.streamChars("</publish>", stream)
    }
    Dump.streamChars("</snapshot>", stream)
    stream.toByteArray
  }

}

