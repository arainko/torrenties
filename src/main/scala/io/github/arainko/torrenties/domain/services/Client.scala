package io.github.arainko.torrenties.domain.services

import io.github.arainko.torrenties.domain.models.network.PeerMessage._
import io.github.arainko.torrenties.domain.models.network._
import io.github.arainko.torrenties.domain.models.state._
import io.github.arainko.torrenties.domain.models.torrent._
import zio._
import zio.logging.{LogAnnotation, Logging, _}
import zio.stream.ZStream

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
        .annotate(LogAnnotation.Timestamp, OffsetDateTime.now)
    }(effect)

  private def worker(
    torrentFile: TorrentFile,
    peer: PeerAddress,
    work: Queue[Work],
    state: PeerInfo
  ) =
    MessageSocket(peer).use { socket =>
      for {
        handshake <- socket.handshake(torrentFile)
        _         <- socket.writeMessage(Interested)
        worker <- socket.readMessage
          .flatMap(m => dispatch(m, peer, state, socket).as(m))
          .repeatUntil(_ == Unchoke)
          .tap(_ => log.debug(s"Unchoked, requesting a piece..."))
        asd <- startDownload(work, state, peer, socket)
      } yield worker
    }

  private def startDownload(
    workQueue: Queue[Work],
    state: PeerInfo,
    peer: PeerAddress,
    socket: MessageSocket
  ) =
    for {
      work   <- workQueue.take //TODO: check if peer has this piece
      pieces <- downloadFullPiece(work, socket)
      fullPiece = FullPiece.fromPieces(pieces)
      _ <- log.debug(s"Validating hashes: ${fullPiece.hash == work.hash}")
    } yield fullPiece

  private def downloadFullPiece(work: Work, socket: MessageSocket) =
    ZStream
      .fromIterable(work.requests)
      .chunkN(5)
      .mapChunksM { requests =>
        for {
          fulfilledRequests <- Ref.make[Chunk[PeerMessage.Piece]](Chunk.empty)
          _                 <- ZIO.foreach(requests)(socket.writeMessage)
          _ <- socket.readMessage
            .flatMap {
              case m: PeerMessage.Piece => fulfilledRequests.update(_.appended(m))
              case other                => log.warn(s"Got different kind of message: $other")
            }
            .repeatUntilM(_ => fulfilledRequests.get.map(_.size == requests.size))
          pieces <- fulfilledRequests.get.map(Chunk.fromIterable)
        } yield pieces
      }
      .runCollect

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
      case Piece(_, _, _)   => ZIO.unit
      case Cancel(_, _, _)  => ZIO.unit
    }) *> state.state.get.map(_.apply(peer)).tap(state => log.debug(s"Peer state: $state"))
}
