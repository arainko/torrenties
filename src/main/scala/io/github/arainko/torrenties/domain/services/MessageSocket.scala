package io.github.arainko.torrenties.domain.services

import io.github.arainko.torrenties.domain.codecs._
import io.github.arainko.torrenties.domain.models.errors._
import io.github.arainko.torrenties.domain.models.network._
import io.github.arainko.torrenties.domain.models.torrent._
import io.github.arainko.torrenties.domain.syntax._
import scodec.codecs._
import zio._
import zio.stream._
import zio.duration._
import zio.logging._
import zio.nio.channels._
import zio.nio.core.SocketAddress

object MessageSocket {

  def apply(peer: PeerAddress): ZManaged[Logging, Exception, MessageSocket] =
    AsynchronousSocketChannel().mapM { socket =>
      SocketAddress
        .inetSocketAddress(peer.address.value, peer.port.value)
        .flatMap(socket.connect)
        .zipRight(ZIO.service[Logger[String]])
        .map(logger => MessageSocket(socket, peer, logger))
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
        _ => logger.debug(s"Handshaked with $peer")
      )
  }

  private def readFully(lenght: Int) =
    Ref.make(lenght).flatMap { leftover =>
      ZStream.repeatEffectChunkOption {
        for {
          left  <- leftover.get.filterOrFail(_ != 0)(None)
          chunk <- socket.readChunk(left).mapError(Option.apply)
          _     <- leftover.update(_ - chunk.length)
        } yield chunk
      }.runCollect
    }

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
}