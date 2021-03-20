package io.github.arainko.torrenties.domain.codecs

import io.github.arainko.torrenties.domain.models.network._
import scodec._
import scodec.codecs._
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets._
import io.github.arainko.torrenties.domain.models.torrent._

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
}
