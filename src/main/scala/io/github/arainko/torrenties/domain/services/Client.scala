package io.github.arainko.torrenties.domain.services

import io.github.arainko.torrenties.domain.models.errors
import io.github.arainko.torrenties.domain.models.network.PeerMessage._
import io.github.arainko.torrenties.domain.models.network._
import io.github.arainko.torrenties.domain.models.state._
import io.github.arainko.torrenties.domain.models.torrent._
import zio._
import zio.logging.{LogAnnotation, Logging, _}
import zio.duration._

import java.time.OffsetDateTime
import scala.annotation.nowarn

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
        .annotate(LogAnnotation.Timestamp, OffsetDateTime.now)
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
        polledWork <- work.take // Specify timeout here?
        _ <- socket.writeMessage(Interested)
        worker <- socket.readMessage
          .flatMap(m => dispatch(m, peer, state, socket).as(m))
          .repeatUntil(_ == Unchoke)
          .tap(_ => log.debug(s"Unchoked, requesting a piece..."))
        _ <- state.hasPiece(peer, polledWork.index).tap(has => log.debug(s"$has"))
        // _ <- ZIO.ifM(state.hasPiece(peer, polledWork.index))
        // _ <- socket.writeMessage(Unchoke)
        _ <- socket.writeMessage(request(polledWork))
        _ <- socket.readMessage
        // _ <- ZIO.sleep(5.seconds)
        // _ <- socket.readMessage.repeatUntil(_ == Unchoke)
        //   .tap(_ => log.debug(s"Unchoked, requesting a piece..."))

      } yield worker
    }

  private def request(work: Work) = Request(UInt32(work.index), UInt32(0), UInt32(Math.pow(2, 14).toLong))

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
      case Request(_, _, _) => ZIO.unit
      case Piece(_, _, _)    => ZIO.unit
      case Cancel(_, _, _)  => ZIO.unit
    }) *> state.state.get.map(_.apply(peer)).tap(state => log.debug(s"Peer state: $state"))
}
