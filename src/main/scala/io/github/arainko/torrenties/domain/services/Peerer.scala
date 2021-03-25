package io.github.arainko.torrenties.domain.services

import zio._
import zio.nio.channels._
import scodec._
import scodec.codecs._
import io.github.arainko.torrenties.domain.models.network._
import io.github.arainko.torrenties.domain.models.torrent._
import io.github.arainko.torrenties.domain.models.errors._
import io.github.arainko.torrenties.domain.codecs._
import io.github.arainko.torrenties.domain.syntax._
import zio.nio.core.InetSocketAddress
import zio.nio.core.SocketAddress
import zio.duration.Duration
import java.util.concurrent.TimeUnit
import shapeless.HNil

object Peerer {

  def handshake(torrentFile: TorrentFile, peer: PeerAddress) =
    AsynchronousSocketChannel().use { socket =>
      for {
        address <- SocketAddress.inetSocketAddress(peer.address.value, peer.port.value)
        _       <- socket.connect(address)
        handshake = Handshake.withDefualts(torrentFile.info.infoHash, PeerId.default)
        encodedHandshake <- Binary.handshake.encodeChunkM(handshake)
        _                <- socket.writeChunk(encodedHandshake)
        response <- socket
          .readChunk(68, Duration(3, TimeUnit.SECONDS))
          .flatMap(Binary.handshake.decodeChunkM)
          .map(_.value)
          .filterOrFail(_.infoHash == handshake.infoHash)(PeerMessageError("Bad handshake!"))
        _ <- communicationLoop(torrentFile, socket).forever
      } yield response
    }

  def communicationLoop(torrentFile: TorrentFile, socket: AsynchronousSocketChannel) =
    for {
      length <- socket
        .readChunk(4, Duration(2, TimeUnit.MINUTES))
        .flatMap(uint32.decodeChunkM)
        .map(_.value)
      message <- socket.readChunk(length.toInt).flatMap(Binary.peerMessageDec(length).decodeSingleChunkM)
      // _ <- (uint32 :: uint8).encodeChunkM(1L :: 1 :: HNil).flatMap(socket.writeChunk)
      _ = println(s"Got $message")
    } yield ()
}
