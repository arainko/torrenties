package io.github.arainko.torrenties.domain.codecs

import io.github.arainko.torrenties.domain.models.network._
import scodec._
import scodec.codecs._
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets._
import io.github.arainko.torrenties.domain.models.torrent._
import scodec.bits.BitVector
import scodec.bits.ByteVector

object Binary {
  val segment: Codec[Segment] = uint8.as[Segment]

  val port: Codec[Port] = uint16.as[Port]

  val ip: Codec[PeerAddress] = (segment :: segment :: segment :: segment :: port).as[PeerAddress]

  val protocolIdLength: Codec[ProtocolIdLength] = uint8.as[ProtocolIdLength]
  val protocolId: Codec[ProtocolId]             = fixedSizeBytes(19, string(UTF_8)).as[ProtocolId]
  val extensionBytes: Codec[ExtensionBytes]     = bytes(8).as[ExtensionBytes]
  val infoHash: Codec[InfoHash]                 = bytes(20).as[InfoHash]
  val peerId: Codec[PeerId]                     = bytes(20).as[PeerId]

  val handshake: Codec[Handshake] =
    (protocolIdLength :: protocolId :: extensionBytes :: infoHash :: peerId).as[Handshake]

  private val uint32t: Codec[UInt32] = uint32.xmapc(UInt32)(_.value)

  def peerMessageDec(length: Long): Decoder[PeerMessage] =
    if (length == 0) Decoder.point(PeerMessage.KeepAlive)
    else
      for {
        id <- uint8
        payloadLength = (length - 1).toInt
        peerMessage <- id match {
          case 0 => Decoder.point(PeerMessage.Choke)
          case 1 => Decoder.point(PeerMessage.Unchoke)
          case 2 => Decoder.point(PeerMessage.Interested)
          case 3 => Decoder.point(PeerMessage.NotInterested)
          case 4 => uint32t.as[PeerMessage.Have]
          case 5 => bytesStrict(payloadLength).as[PeerMessage.Bitfield]
          case 6 => (uint32t :: uint32t :: uint32t).as[PeerMessage.Request]
          case 7 => (uint32t :: uint32t :: bytesStrict(payloadLength - 8)).as[PeerMessage.Piece]
          case 8 => (uint32t :: uint32t :: uint32t).as[PeerMessage.Cancel]
        }
      } yield peerMessage

  def peerMessageEnc: Encoder[PeerMessage] =
    Encoder[PeerMessage] { message: PeerMessage =>
      message match {
        case PeerMessage.KeepAlive          => uint32.encode(0)
        case PeerMessage.Choke              => (uint32 ~ uint8).encode(4L -> 1)
        case PeerMessage.Unchoke            => (uint32 ~ uint8).encode(4L -> 2)
        case PeerMessage.Interested         => (uint32 ~ uint8).encode(4L -> 2)
        case PeerMessage.NotInterested      => (uint32 ~ uint8).encode(4L -> 3)
        case PeerMessage.Have(index)        => uint32t.encode(index)
        case PeerMessage.Bitfield(bitfield) => bytes.encode(bitfield)
        case m @ PeerMessage.Request(_, _, _) =>
          (uint32t :: uint32t :: uint32t).as[PeerMessage.Request].encode(m)
        case m @ PeerMessage.Piece(_, _, _) =>
          (uint32t :: uint32t :: bytes).as[PeerMessage.Piece].encode(m)
        case m @ PeerMessage.Cancel(_, _, _) =>
          (uint32t :: uint32t :: uint32t).as[PeerMessage.Cancel].encode(m)
      }
    }
}
