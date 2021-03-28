package io.github.arainko.torrenties.domain.services

import io.github.arainko.torrenties.domain.codecs._
import io.github.arainko.torrenties.domain.models.errors._
import io.github.arainko.torrenties.domain.models.network._
import io.github.arainko.torrenties.domain.models.torrent._
import io.github.arainko.torrenties.domain.models.state._
import io.github.arainko.torrenties.domain.syntax._
import scodec.codecs._
import zio._
import zio.duration.Duration
import zio.nio.channels._
import zio.nio.core.SocketAddress

import java.util.concurrent.TimeUnit
import scala.annotation.nowarn
import io.github.arainko.torrenties.domain.models.torrent.Info.SingleFile
import io.github.arainko.torrenties.domain.models.torrent.Info.MultipleFile

import shapeless.ops.tuple._

object Client {

  def start(torrentFile: TorrentFile) =
    for {
      announce <- Tracker.announce(torrentFile)
      // _ = torrentFile.info.fold(a => a.piece, a => a.pieceLength)
      workPieces = torrentFile.info.fold(
        s => s.hashPieces.zipWithIndex.map { case (hash, index) => Work(index.toLong, hash, s.pieceLength) },
        s => s.hashPieces.zipWithIndex.map { case (hash, index) => Work(index.toLong, hash, s.pieceLength) },
      )
      workQueue   <- Queue.bounded[Work](workPieces.size)
      resultQueue <- Queue.bounded[Work](workPieces.size) //TODO: Add Result type
      _           <- workQueue.offerAll(workPieces)
      initialPeerState = announce.peers.map(_ -> PeerState.initial(workPieces.size.toLong)).toMap
      peerState <- Ref.make(initialPeerState)
      fibers <- ZIO.foreach(announce.peers) { peer =>
        handshake(torrentFile, peer, workQueue, peerState).fork
      }
      _ <- workQueue.awaitShutdown
    } yield ()

  @nowarn
  private def handshake(
    torrentFile: TorrentFile,
    peer: PeerAddress,
    work: Queue[Work],
    state: Ref[Map[PeerAddress, PeerState]]
  ) =
    AsynchronousSocketChannel().use { socket =>
      for {
        address <- SocketAddress.inetSocketAddress(peer.address.value, peer.port.value)
        _       <- socket.connect(address).tapError(e => ZIO(print(e.getMessage)))
        handshake = Handshake.withDefualts(torrentFile.info.infoHash, PeerId.default)
        encodedHandshake <- Binary.handshake.encodeChunkM(handshake)
        _                <- socket.writeChunk(encodedHandshake)
        response <- socket
          .readChunk(68, Duration(3, TimeUnit.SECONDS))
          .flatMap(Binary.handshake.decodeChunkM)
          .map(_.value)
          .filterOrFail(_.infoHash == handshake.infoHash)(PeerMessageError("Bad handshake!"))
        polledWork <- work.take
        never <- communicationLoop(polledWork, work, peer, state, socket).forever
      } yield never
    }

  private def communicationLoop(
    work: Work,
    workQueue: Queue[Work],
    peer: PeerAddress,
    state: Ref[Map[PeerAddress, PeerState]],
    socket: AsynchronousSocketChannel
  ): ZIO[Any, Throwable, Unit] =
    for {
      length <- socket
        .readChunk(4, Duration(2, TimeUnit.MINUTES))
        .flatMap(uint32.decodeChunkM)
        .map(_.value)
      _ = println(s"polled $work")  
      message <- socket.readChunk(length.toInt).flatMap(Binary.peerMessageDec(length).decodeSingleChunkM)
      // _ <- (uint32 :: uint8).encodeChunkM(1L :: 1 :: HNil).flatMap(socket.writeChunk)
      _ = println(s"Got $message")
    } yield ()
}
