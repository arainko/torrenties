package io.github.arainko.torrenties.domain.services

import io.github.arainko.torrenties.domain.models.network.PeerMessage._
import io.github.arainko.torrenties.domain.models.network._
import io.github.arainko.torrenties.domain.models.state._
import io.github.arainko.torrenties.domain.models.torrent._
import zio._

import scala.annotation.nowarn
import zio.logging.Logger
import zio.logging.Logging
import zio.logging.LogAnnotation

object Client {

  def start(torrentFile: TorrentFile) =
    for {
      logger   <- ZIO.service[Logger[String]]
      announce <- Tracker.announce(torrentFile)
      // _ = torrentFile.info.fold(a => a.piece, a => a.pieceLength)
      workPieces = torrentFile.info.fold(
        s => s.hashPieces.zipWithIndex.map { case (hash, index) => Work(index.toLong, hash, s.pieceLength) },
        s => s.hashPieces.zipWithIndex.map { case (hash, index) => Work(index.toLong, hash, s.pieceLength) }
      )
      workQueue   <- Queue.bounded[Work](workPieces.size)
      resultQueue <- Queue.bounded[Work](workPieces.size) //TODO: Add Result type
      _           <- workQueue.offerAll(workPieces)
      peerState   <- PeerInfo.make(announce.peers, workPieces.size.toLong)
      fibers <- ZIO.foreach(announce.peers) { peer =>
        println(s"Started $peer")
        Logging.locally(context => context.annotate(LogAnnotation.Name, peer.address.value :: Nil)) {
          worker(torrentFile, peer, workQueue, peerState, logger).fork
        }
      }
      _ <- workQueue.awaitShutdown
    } yield ()

    ZManaged.scope

  @nowarn
  private def worker(
    torrentFile: TorrentFile,
    peer: PeerAddress,
    work: Queue[Work],
    state: PeerInfo,
    logger: Logger[String]
  ) =
    MessageSocket(peer, logger).use { socket =>
      for {
        handshake <- socket.handshake(torrentFile)
        // _         <- socket.writeMessage(PeerMessage.Unchoke)
        worker <- socket.readMessage
          // .flatMap(m => dispatch(m, peer, state, socket))
          // .forever
          // .fork
      } yield worker
    }

  // private def communicationLoop(
  //   workQueue: Queue[Work],
  //   peer: PeerAddress,
  //   state: Ref[Map[PeerAddress, PeerState]],
  //   socket: AsynchronousSocketChannel
  // ): ZIO[Any, Throwable, Unit] =
  //   for {
  //     length <- socket
  //       .readChunk(4, Duration(2, TimeUnit.MINUTES))
  //       .flatMap(uint32.decodeChunkM)
  //       .map(_.value)
  //     _ = println(s"polled $work")
  //     message <- socket
  //       .readChunk(length.toInt)
  //       .flatMap(Binary.peerMessageDec(length).decodeSingleChunkM)
  //     _ <- dispatch(message, work, peer, state, socket)
  //     _ = println(s"Got $message")
  //   } yield ()

  private def dispatch(
    message: PeerMessage,
    peer: PeerAddress,
    state: PeerInfo,
    socket: MessageSocket
  ) =
    message match {
      case KeepAlive =>
        socket.writeMessage(KeepAlive)
      case Choke =>
        state.updatePeerChoke(peer, ChokeState.Choked)
      case Unchoke =>
        state.updatePeerChoke(peer, ChokeState.Unchoked)
      case Interested =>
        state.updatePeerInterest(peer, InterestState.Interested)
      case NotInterested =>
        state.updatePeerInterest(peer, InterestState.NotInterested)
      case Have(pieceIndex) =>
        state.updateBitfield(peer, pieceIndex.value, true)
      case Bitfield(payload) =>
        state.setBitfield(peer, payload)
      case Request(pieceIndex, begin, length) => ZIO.unit
      case Piece(pieceIndex, begin, block)    => ZIO.unit
      case Cancel(pieceIndex, begin, length)  => ZIO.unit
    }
}
