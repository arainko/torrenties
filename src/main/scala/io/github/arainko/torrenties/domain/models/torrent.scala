package io.github.arainko.torrenties.domain.models

import io.github.arainko.bencode.syntax._
import io.github.arainko.torrenties.domain.codecs.Binary._
import io.github.arainko.torrenties.domain.codecs.bencode._
import io.github.arainko.torrenties.domain.models.network._
import io.github.arainko.torrenties.domain.models.torrent.Info.{MultipleFile, SingleFile}
import io.scalaland.chimney.dsl._
import scodec.bits.ByteVector

import java.time.{Duration, LocalDate}

object torrent {
  final case class Seeders(value: Long)        extends AnyVal
  final case class Leechers(value: Long)       extends AnyVal
  final case class InfoHash(value: ByteVector) extends AnyVal
  final case class PeerId(value: ByteVector)   extends AnyVal

  object PeerId {

    val default: PeerId = PeerId {
      ByteVector.view("-TRTS01-".getBytes).padRight(20)
    }
  }

  final case class Subfile(length: Long, path: Seq[String])

  sealed trait Info {

    final def fold[A](single: SingleFile => A, multiple: MultipleFile => A): A =
      this match {
        case s: SingleFile   => single(s)
        case m: MultipleFile => multiple(m)
      }

    lazy val infoHash: InfoHash = InfoHash(this.asBencode.byteify().digest("SHA-1"))

    lazy val hashPieces: List[ByteVector] = 
      List.unfold(fold(_.pieces, _.pieces)) { curr =>
        val piece     = curr.take(20)
        val remainder = curr.drop(20)
        Option.when(!piece.isEmpty)(piece -> remainder)
      }
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

    lazy val httpAnnounces: Seq[String] = (announce +: announceList.map(_.flatten).getOrElse(Seq.empty))
      .filter(_.startsWith("http"))
  }

  final case class AnnounceResponse(
    interval: Long,
    complete: Option[Long],
    incomplete: Option[Long],
    peers: ByteVector
  ) {

    lazy val ips: Seq[PeerAddress] = List.unfold(peers.bits) { bits =>
      ip.decode(bits).toOption.map(r => r.value -> r.remainder)
    }

    def toDomain: Announce =
      this
        .into[Announce]
        .withFieldComputed(_.interval, r => Duration.ofSeconds(r.interval))
        .withFieldComputed(_.peers, _.ips)
        .transform

  }

  final case class Announce(
    interval: Duration,
    complete: Option[Seeders],
    incomplete: Option[Leechers],
    peers: Seq[PeerAddress]
  )
}
