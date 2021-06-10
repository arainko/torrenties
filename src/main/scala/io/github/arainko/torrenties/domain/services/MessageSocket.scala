package io.github.arainko.torrenties.domain.services

import io.github.arainko.torrenties.domain.codecs._
import io.github.arainko.torrenties.domain.models.errors._
import io.github.arainko.torrenties.domain.models.network._
import io.github.arainko.torrenties.domain.models.torrent._
import io.github.arainko.torrenties.domain.syntax._
import scodec.codecs._
import zio._
import zio.duration._
import zio.logging._
import zio.nio.channels._
import zio.nio.core.SocketAddress
import zio.stream._

object MessageSocket {

  def apply(peer: PeerAddress): ZManaged[Logging, Exception, MessageSocket] =
    AsynchronousSocketChannel().mapM { socket =>
      SocketAddress
        .inetSocketAddress(peer.address.value, peer.port.value)
        .flatMap(socket.connect)
        .zipRight(ZIO.service[Logger[String]])
        .map(logger => MessageSocket(socket, peer, logger))
    }

  def fromConnectedSocket(socket: AsynchronousSocketChannel): ZIO[Logging, Exception, MessageSocket] =
    for {
      _ <- socket.localAddress.debug
      address <- socket.remoteAddress.debug
        .map(_.flatMap(PeerAddress.fromSocketAddress))
        .unrefineTo[Exception]
        .someOrFailException
      logger <- ZIO.service[Logger[String]]
    } yield MessageSocket(socket, address, logger)
}

final case class MessageSocket(socket: AsynchronousSocketChannel, peer: PeerAddress, logger: Logger[String]) {

  def initializeHandshake(torrentFile: TorrentFile): Task[Handshake] = {
    val handshake = Handshake.withDefualts(torrentFile.info.infoHash, PeerId.default)
    Binary.handshake
      .encodeChunkM(handshake)
      .flatMap(socket.writeChunk)
      .zipRight(socket.readChunk(68, 3.seconds))
      .flatMap(Binary.handshake.decodeSingleChunkM)
      .filterOrFail(_.infoHash == handshake.infoHash)(PeerMessageError("Bad handshake!"))
      .tapBoth(
        err => logger.debug(s"Handshake failed with error: $err"),
        _ => logger.debug(s"Handshaked with $peer")
      )
  }

  def awaitHandshake(torrentFile: TorrentFile) = {
    val handshake = Handshake.withDefualts(torrentFile.info.infoHash, PeerId.default)
    for {
      incomingHandshake <- socket
        .readChunk(68, 30.seconds)
        .flatMap(Binary.handshake.decodeSingleChunkM)
      _ <- Binary.handshake
        .encodeChunkM(handshake)
        .flatMap(socket.writeChunk)
      validatedHandshake <- ZIO.cond(
        incomingHandshake.infoHash == handshake.infoHash,
        incomingHandshake,
        PeerMessageError("Bad handshake!")
      )
    } yield validatedHandshake
  }
    .tapBoth(
      err => logger.debug(s"Handshake failed with error: $err"),
      _ => logger.debug(s"Handshaked with $peer")
    )

  def readMessage: Task[PeerMessage] = {
    for {
      length <- socket
        .readChunk(4, 2.minutes)
        .flatMap(uint32.decodeSingleChunkM)
        .tap(length => logger.debug(s"Message with lenght ${length.toInt} incoming"))
      chunk   <- readFully(length.toInt)
      message <- Binary.peerMessageDec(length).decodeSingleChunkM(chunk)
    } yield message
  }
    .tapBoth(
      err => logger.debug(s"Read message failed with $err"),
      msg => logger.debug(s"Read message: $msg")
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

  private def readFully(length: Int) =
    ZStream
      .unfoldChunkM(length) { leftover =>
        if (leftover == 0) ZIO.none
        else
          socket
            .readChunk(leftover)
            .map(chunk => Some(chunk -> (leftover - chunk.size)))
      }
      .runCollect
}
