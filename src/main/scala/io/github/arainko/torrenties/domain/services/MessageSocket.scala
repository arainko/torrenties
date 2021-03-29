package io.github.arainko.torrenties.domain.services

import io.github.arainko.torrenties.domain.codecs._
import io.github.arainko.torrenties.domain.models.network._
import io.github.arainko.torrenties.domain.models.torrent._
import io.github.arainko.torrenties.domain.models.errors._
import io.github.arainko.torrenties.domain.syntax._
import scodec.codecs._
import zio._
import zio.duration._
import zio.nio.channels._
import zio.nio.core.SocketAddress

import java.util.concurrent.TimeUnit
import zio.stream.ZStream
import zio.logging._
import io.github.arainko.torrenties.domain.models.errors

object MessageSocket {

  def apply(peer: PeerAddress, logger: Logger[String]): TaskManaged[MessageSocket] =
    AsynchronousSocketChannel().mapM { socket =>
      SocketAddress
        .inetSocketAddress(peer.address.value, peer.port.value)
        .flatMap(socket.connect)
        .as(MessageSocket(socket, peer, logger))
    }
}

final case class MessageSocket(socket: AsynchronousSocketChannel, peer: PeerAddress, logger: Logger[String]) {

  def handshake(torrentFile: TorrentFile): Task[Handshake] = {
    val handshake = Handshake.withDefualts(torrentFile.info.infoHash, PeerId.default)
    Binary.handshake
      .encodeChunkM(handshake)
      .flatMap(socket.writeChunk)
      .zipRight(socket.readChunk(68, 3.seconds))
      .flatMap(Binary.handshake.decodeSingleChunkM)
      .filterOrFail(_.infoHash == handshake.infoHash)(PeerMessageError("Bad handshake!"))
      .tapBoth(
        err => logger.debug(s"Handshake failed with error: $err"),
        handshake => logger.debug(s"Got handshake back: $handshake")
      )
  }

  def readMessage: Task[PeerMessage] =
    (for {
      length <- socket
        .readChunk(4, 2.minutes)
        .flatMap(uint32.decodeSingleChunkM)
      _       <- logger.debug(s"Message with lenght $length incoming")
      chunk   <- socket.readChunk(length.toInt)
      message <- Binary.peerMessageDec(length).decodeSingleChunkM(chunk)
    } yield message)
      .tapBoth(
        err => logger.debug(s"Read message failed with $err: ${err.getMessage}"),
        msg => logger.debug(s"Got $msg back")
      )

  def writeMessage(message: PeerMessage): Task[Unit] =
    Binary.peerMessageEnc
      .encodeChunkM(message)
      .flatMap(socket.writeChunk)
      .unit
      .tapBoth(
        err => logger.debug(s"Failed to write $message with error: $err"),
        _ => logger.debug(s"Wrote $message")
      )
}
