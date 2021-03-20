package io.github.arainko.torrenties.domain.models

import cats.syntax.either._
import errors._
import io.github.arainko.torrenties.domain.models.torrent._
import scodec.bits.ByteVector
import io.github.arainko.torrenties.domain.models.network.PeerMessage._
import zio.Ref

object network {
  final case class IPAddress(value: String)          extends AnyVal
  final case class Segment(value: Int)               extends AnyVal
  final case class Port(value: Int)                  extends AnyVal
  final case class ProtocolIdLength(value: Int)      extends AnyVal
  final case class ProtocolId(value: String)         extends AnyVal
  final case class ExtensionBytes(value: ByteVector) extends AnyVal

  object ExtensionBytes {
    def default: ExtensionBytes = ExtensionBytes(ByteVector.fill(8)(0))
  }

  object ProtocolId {
    def default: ProtocolId             = ProtocolId("BitTorrent protocol")
    def defaultLenght: ProtocolIdLength = ProtocolIdLength(19)
  }

  final case class PeerAddress(seg1: Segment, seg2: Segment, seg3: Segment, seg4: Segment, port: Port) {
    lazy val address: IPAddress = IPAddress(s"${seg1.value}.${seg2.value}.${seg3.value}.${seg4.value}")

    def show: String = s"${seg1.value}.${seg2.value}.${seg3.value}.${seg4.value}:${port.value}"
  }

  final case class Handshake(
    protocolStringLength: ProtocolIdLength,
    protocolIdentifier: ProtocolId,
    extensionBytes: ExtensionBytes,
    infoHash: InfoHash,
    peerId: PeerId
  )

  object Handshake {

    def withDefualts(infoHash: InfoHash, peerId: PeerId): Handshake =
      Handshake(
        ProtocolId.defaultLenght,
        ProtocolId.default,
        ExtensionBytes.default,
        infoHash,
        peerId
      )
  }

  final case class PeerMessageId(value: Int) extends AnyVal

  sealed trait PeerMessage

  object PeerMessage {

    case object KeepAlive extends PeerMessage

    sealed trait Meaningful extends PeerMessage {

      def id: PeerMessageId =
        PeerMessageId {
          this match {
            case Meaningful.Choke         => 0
            case Meaningful.Unchoke       => 1
            case Meaningful.Interested    => 2
            case Meaningful.NotInterested => 3
            case Meaningful.Have          => 4
            case Meaningful.Bitfield      => 5
            case Meaningful.Request       => 6
            case Meaningful.Piece         => 7
            case Meaningful.Cancel        => 8
          }
        }
    }

    object Meaningful {
      case object Choke         extends PeerMessage.Meaningful
      case object Unchoke       extends PeerMessage.Meaningful
      case object Interested    extends PeerMessage.Meaningful
      case object NotInterested extends PeerMessage.Meaningful
      case object Have          extends PeerMessage.Meaningful
      case object Bitfield      extends PeerMessage.Meaningful
      case object Request       extends PeerMessage.Meaningful
      case object Piece         extends PeerMessage.Meaningful
      case object Cancel        extends PeerMessage.Meaningful

      def fromId(id: PeerMessageId): PeerMessage.Meaningful =
        id.value match {
          case 0       => Meaningful.Choke
          case 1       => Meaningful.Unchoke
          case 2       => Meaningful.Interested
          case 3       => Meaningful.NotInterested
          case 4       => Meaningful.Have
          case 5       => Meaningful.Bitfield
          case 6       => Meaningful.Request
          case 7       => Meaningful.Piece
          case 8       => Meaningful.Cancel
          // case other => Left()
        }
    }

  }
}
