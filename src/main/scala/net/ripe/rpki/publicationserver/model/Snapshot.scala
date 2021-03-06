package net.ripe.rpki.publicationserver.model

import java.io.ByteArrayOutputStream
import java.net.URI

import net.ripe.rpki.publicationserver.Binaries.Bytes
import net.ripe.rpki.publicationserver.{Formatting, Hashing}

case class Snapshot(serverState: ServerState, pdus: Seq[(Bytes, URI)]) extends Hashing with Formatting {

  lazy val bytes = serialize
  lazy val contentHash = hash(bytes)
  lazy val binarySize = bytes.length

  private[model] def serialize = {
    val ServerState(sessionId, serial) = serverState
    val stream = new ByteArrayOutputStream()
    Dump.streamChars(s"""<snapshot version="1" session_id="$sessionId" serial="$serial" xmlns="http://www.ripe.net/rpki/rrdp">\n""", stream)
    pdus.foreach { pdu =>
      val (bytes, uri) = pdu
      Dump.streamChars(s"""<publish uri="${attr(uri.toASCIIString)}">""", stream)
      Dump.streamChars(Bytes.toBase64(bytes).value, stream)
      Dump.streamChars("</publish>\n", stream)
    }
    Dump.streamChars("</snapshot>", stream)
    stream.toByteArray
  }

}

