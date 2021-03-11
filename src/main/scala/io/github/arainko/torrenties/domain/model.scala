package io.github.arainko.torrenties.domain

import scodec.bits.ByteVector

object model {

  final case class Subfile(length: Long, path: Seq[String])

  sealed trait Info

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
    announce: String
  )
}
