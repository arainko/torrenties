package io.github.arainko.torrenties.domain.services

import io.github.arainko.torrenties.domain.models.network.PeerMessage._
import io.github.arainko.torrenties.domain.models.network._
import io.github.arainko.torrenties.domain.models.state._
import io.github.arainko.torrenties.domain.models.torrent._
import zio._
import zio.logging._

import scala.annotation.nowarn
import zio.logging.Logger
import zio.logging.Logging
import zio.logging.LogAnnotation
import java.time.OffsetDateTime

object Client {

  def start(torrentFile: TorrentFile) =
    for {
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
        logged(peer) {
          log.debug(s"Starting $peer") *>
            worker(torrentFile, peer, workQueue, peerState).fork
        }
      }
      _ <- workQueue.awaitShutdown
    } yield ()

  private def logged[R, E, A](peer: PeerAddress)(effect: ZIO[R, E, A]) =
    Logging.locally { ctx =>
      ctx
        .annotate(LogAnnotation.Name, peer.address.value :: Nil)
        .annotate(LogAnnotation.Timestamp, OffsetDateTime.now())
    }(effect)

  @nowarn
  private def worker(
    torrentFile: TorrentFile,
    peer: PeerAddress,
    work: Queue[Work],
    state: PeerInfo,
  ) =
    MessageSocket(peer).use { socket =>
      for {
        handshake <- socket.handshake(torrentFile)
        worker <- socket.readMessage
          .flatMap(m => dispatch(m, peer, state, socket).as(m))
          .repeatUntil(_ == PeerMessage.Unchoke)
          .tap(_ => log.debug(s"GOT UNCHOKE CAN GO FORWARD NOW"))
      } yield worker
    }

  private def dispatch(
    message: PeerMessage,
    peer: PeerAddress,
    state: PeerInfo,
    socket: MessageSocket
  ) =
    (message match {
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
    }) *> state.state.get.map(_.apply(peer)).tap(state => log.debug(s"Peer state: $state"))
}
