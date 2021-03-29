package io.github.arainko.torrenties.domain.models

import io.github.arainko.torrenties.domain.models.network.PeerMessage._
import io.github.arainko.torrenties.domain.models.torrent._
import scodec.bits.ByteVector
import scodec.bits.BitVector

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

    override def toString: String = s"${seg1.value}.${seg2.value}.${seg3.value}.${seg4.value}:${port.value}"
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

  final case class UInt32(value: Long)       extends AnyVal
  final case class PeerMessageId(value: Int) extends AnyVal

  sealed trait PeerMessage {

    final lazy val totalLength: UInt32 =
      this match {
        case KeepAlive          => UInt32(0)
        case Choke              => UInt32(1)
        case Unchoke            => UInt32(1)
        case Interested         => UInt32(1)
        case NotInterested      => UInt32(1)
        case Have(_)            => UInt32(5)
        case Bitfield(payload)  => UInt32(1 + payload.bytes.length)
        case Request(_, _, _)   => UInt32(13)
        case Piece(_, _, block) => UInt32(9 + block.length)
        case Cancel(_, _, _)    => UInt32(13)
      }
  }

  object PeerMessage {
    case object KeepAlive extends PeerMessage

    case object Choke extends PeerMessage

    case object Unchoke extends PeerMessage

    case object Interested extends PeerMessage

    case object NotInterested extends PeerMessage

    final case class Have(pieceIndex: UInt32) extends PeerMessage

    final case class Bitfield(payload: BitVector) extends PeerMessage

    final case class Request(pieceIndex: UInt32, begin: UInt32, length: UInt32) extends PeerMessage

    final case class Piece(pieceIndex: UInt32, begin: UInt32, block: ByteVector) extends PeerMessage

    final case class Cancel(pieceIndex: UInt32, begin: UInt32, length: UInt32) extends PeerMessage
  }

}
