package io.github.arainko.torrenties.domain.codecs

import io.github.arainko.torrenties.domain.models.network.PeerMessage._
import io.github.arainko.torrenties.domain.models.network._
import io.github.arainko.torrenties.domain.models.torrent._
import scodec._
import scodec.codecs._

import java.nio.charset.StandardCharsets._

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
          case 0  => Decoder.point(Choke)
          case 1  => Decoder.point(Unchoke)
          case 2  => Decoder.point(Interested)
          case 3  => Decoder.point(NotInterested)
          case 4  => uint32t.as[Have]
          case 5  => bytes(payloadLength).map(_.bits).asDecoder.as[Bitfield]
          case 6  => (uint32t :: uint32t :: uint32t).as[Request]
          case 7  => (uint32t :: uint32t :: bytes(payloadLength - 8)).as[Piece]
          case 8  => (uint32t :: uint32t :: uint32t).as[Cancel]
          case id => Decoder.liftAttempt(Attempt.failure(Err(s"Message with id = '$id' not supported")))
        }
      } yield peerMessage

  private val lengthAndId = uint32 ~ uint8

  val peerMessageEnc: Encoder[PeerMessage] =
    Encoder { message: PeerMessage =>
      message match {
        case KeepAlive     => uint32.encode(0)
        case Choke         => lengthAndId.encode(1L ~ 0)
        case Unchoke       => lengthAndId.encode(1L ~ 1)
        case Interested    => lengthAndId.encode(1L ~ 2)
        case NotInterested => lengthAndId.encode(1L ~ 3)
        case Have(index)   => (lengthAndId ~ uint32t).encode(5L ~ 4 ~ index)
        case m: Bitfield =>
          (lengthAndId ~ codecs.bits)
            .encode(m.totalLength.value ~ 5 ~ m.payload)
        case m: Request =>
          (lengthAndId ~ (uint32t :: uint32t :: uint32t).as[Request])
            .encode(13L ~ 6 ~ m)
        case m: Piece =>
          (lengthAndId ~ (uint32t :: uint32t :: bytes).as[Piece])
            .encode(m.totalLength.value ~ 7 ~ m)
        case m: Cancel =>
          (lengthAndId ~ (uint32t :: uint32t :: uint32t).as[Cancel])
            .encode(13L ~ 8 ~ m)
      }
    }
}
