package io.github.arainko.torrenties.domain.services

import io.github.arainko.torrenties._
import io.github.arainko.torrenties.domain.syntax._
import io.github.arainko.torrenties.domain.models.errors._
import io.github.arainko.torrenties.domain.models.network.PeerMessage._
import io.github.arainko.torrenties.domain.models.network._
import io.github.arainko.torrenties.domain.models.state._
import io.github.arainko.torrenties.domain.models.torrent._
import zio._
import zio.blocking.Blocking
import zio.clock.Clock
import zio.duration._
import zio.logging.{LogAnnotation, Logging, _}
import zio.stream.ZStream

import java.time.OffsetDateTime
import scala.annotation.nowarn

object Client {

  def start(torrentFile: TorrentFile): ZIO[Tracker with Blocking with Logging with Clock, TrackerError, Unit] =
    for {
      announce <- Tracker.announce(torrentFile)
      workPieces = torrentFile.info.workPieces
      workQueue   <- Queue.bounded[Work](workPieces.size)
      resultQueue <- Queue.bounded[Result](workPieces.size)
      _           <- Merger.daemon(torrentFile, resultQueue).runDrain.fork
      _           <- workQueue.offerAll(workPieces)
      peerState   <- PeerInfo.make(announce.peers, workPieces.size.toLong)
      _ <- ZIO.foreach(announce.peers) { peer =>
        logged(peer) {
          worker(torrentFile, peer, workQueue, resultQueue, peerState).fork
            .zipLeft(log.debug(s"Started $peer"))
        }
      }
      _ <- workQueue.awaitShutdown
    } yield ()

  @nowarn
  private def worker(
    torrentFile: TorrentFile,
    peer: PeerAddress,
    work: Queue[Work],
    results: Queue[Result],
    state: PeerInfo
  ) =
    MessageSocket(peer).use { socket =>
      for {
        _ <- socket.handshake(torrentFile)
        _ <- socket.writeMessage(Interested)
        _ <- socket.readMessage
          .flatMap(msg => dispatch(msg, peer, state, socket).as(msg))
          .repeatUntil(_ == Unchoke)
          .tap(_ => log.debug(s"Unchoked, requesting a piece..."))
        asd <- startDownload(work, results, state, peer, socket).forever
      } yield asd
    }

  private def takeIfHas(q: Queue[Work], predM: Work => UIO[Boolean]): URIO[Clock, Work] =
    q.take.flatMap { taken =>
      ZIO.ifM(predM(taken))(
        ZIO.succeed(taken),
        q.offer(taken) *> ZIO.sleep(50.millis) *> takeIfHas(q, predM)
      )
    }

  private def startDownload(
    workQueue: Queue[Work],
    resultQueue: Queue[Result],
    state: PeerInfo,
    peer: PeerAddress,
    socket: MessageSocket
  ) =
    for {
      work <- takeIfHas(workQueue, w => state.hasPiece(peer, w.index))
      pieces <- downloadFullPiece(work, socket)
        .timeoutFail(TimeoutError)(2.minutes)
        .onError(_ => workQueue.offer(work))
      fullPiece = FullPiece.fromPieces(pieces)
      result    = Result(work, fullPiece)
      _ <- if (fullPiece.hash == work.hash) resultQueue.offer(result) else workQueue.offer(work)
      _ <- log.debug(s"Validating hashes: ${fullPiece.hash == work.hash}")
    } yield fullPiece

  private def downloadFullPiece(work: Work, socket: MessageSocket) =
    ZStream
      .fromIterable(work.requests)
      .chunkN(5)
      .mapChunksM { requests =>
        for {
          fulfilledRequests <- Ref.make[Chunk[Piece]](Chunk.empty)
          _                 <- ZIO.foreach(requests)(socket.writeMessage)
          _ <- socket.readMessage
            .flatMap {
              case piece: Piece => fulfilledRequests.update(_.appended(piece))
              case other        => log.warn(s"Got different kind of message: $other")
            }
            .repeatUntilM(_ => fulfilledRequests.get.map(_.size == requests.size))
          pieces <- fulfilledRequests.get
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

  private def logged[R, E, A](peer: PeerAddress)(effect: ZIO[R, E, A]): ZIO[Logging with R, E, A] =
    Logging.locally { ctx =>
      ctx
        .annotate(LogAnnotation.Name, peer.address.value :: Nil)
        .annotate(LogAnnotation.Timestamp, OffsetDateTime.now)
    }(effect)

}
