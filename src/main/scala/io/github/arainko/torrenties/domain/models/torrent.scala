package io.github.arainko.torrenties.domain.models

import io.github.arainko.torrenties.domain.codecs.bencode._
import io.github.arainko.torrenties.domain.codecs.binary._
import io.github.arainko.torrenties.domain.models.network._
import rainko.bencode.syntax._
import io.scalaland.chimney.dsl._
import scodec.bits.ByteVector

import java.time.{Duration, LocalDate}

object torrent {
  final case class Subfile(length: Long, path: Seq[String])

  sealed trait Info {
    lazy val infoHash: ByteVector = this.encode.byteify().digest("SHA-1")
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
  ) {

    lazy val httpAnnounces = (announce +: announceList.map(_.flatten).getOrElse(Seq.empty))
      .filter(_.startsWith("http"))
  }

  final case class Seeders(value: Long)  extends AnyVal
  final case class Leechers(value: Long) extends AnyVal

  final case class AnnounceResponseRaw(
    interval: Long,
    complete: Option[Long],
    incomplete: Option[Long],
    peers: ByteVector
  ) {

    lazy val ips: Seq[IP] = List.unfold(peers.bits) { bits =>
      ip.decode(bits).toOption.map(r => r.value -> r.remainder)
    }

    def toDomain: AnnounceResponse =
      this
        .into[AnnounceResponse]
        .withFieldComputed(_.interval, r => Duration.ofSeconds(r.interval))
        .withFieldComputed(_.peers, _.ips)
        .transform

  }

  final case class AnnounceResponse(
    interval: Duration,
    complete: Option[Seeders],
    incomplete: Option[Leechers],
    peers: Seq[IP]
  ) {}
}
