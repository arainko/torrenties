package io.github.arainko.torrenties.domain

import scodec.bits.ByteVector
import java.time.LocalDate
import rainko.bencode.syntax._
import io.github.arainko.torrenties.infrastructure.codecs._
import java.security.MessageDigest

object model {

  final case class Subfile(length: Long, path: Seq[String])

  sealed trait Info {
    final def infoHash: ByteVector = this.encode.byteify().digest("SHA-1")
  }

  object Info {

    final case class SingleFile(
      pieceLength: Long,
      pieces: ByteVector,
      name: String,
      length: Long
    ) extends Info

    final case class MultipleFile(
      pieceLength: Long,
      pieces: ByteVector,
      name: String,
      files: Seq[Subfile]
    ) extends Info
  }

  final case class TorrentFile(
    info: Info,
    announce: String,
    announceList: Option[Seq[Seq[String]]] = None,
    creationDate: Option[LocalDate] = None,
    comment: Option[String] = None,
    createdBy: Option[String] = None,
    encoding: Option[String] = None
  )
}
