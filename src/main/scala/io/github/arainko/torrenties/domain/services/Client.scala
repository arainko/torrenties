package io.github.arainko.torrenties.domain.services

import io.github.arainko.torrenties._
import io.github.arainko.torrenties.domain.models.errors._
import io.github.arainko.torrenties.domain.models.network.PeerMessage._
import io.github.arainko.torrenties.domain.models.network.PeerMessage
import io.github.arainko.torrenties.domain.models.network._
import io.github.arainko.torrenties.domain.models.state._
import io.github.arainko.torrenties.domain.models.torrent._
import io.github.arainko.torrenties.domain.syntax._
import zio._
import zio.clock.Clock
import zio.duration._
import zio.console._
import zio.logging.{LogAnnotation, Logging, _}
import zio.stream.ZStream

import java.time.OffsetDateTime
import scala.annotation.nowarn
import zio.stream.ZTransducer

object Client {

  def start(
    metaRef: Ref[TorrentMeta]
  ): ZIO[Tracker with Merger with Logging with ZEnv, TrackerError, Unit] =
    for {
      meta <- metaRef.get
      torrentFile = meta.torrentFile
      announce    <- Tracker.announce(torrentFile)
      workQueue   <- Queue.bounded[Work](torrentFile.pieceCount.toInt)
      resultQueue <- Queue.bounded[Result](torrentFile.pieceCount.toInt)
      _ <- Merger
        .daemon(torrentFile, resultQueue)
        .transduce(transducer(metaRef, resultQueue))
        .runDrain
        .fork
      _         <- workQueue.offerAll(meta.incompleteWork)
      peerState <- PeerInfo.make(announce.peers, meta.incompleteWork.size.toLong)
      _ <- ZIO.foreach(announce.peers) { peer =>
        logged(peer) {
          worker(torrentFile, peer, workQueue, resultQueue, peerState).fork
            .zipLeft(log.debug(s"Started $peer"))
        }
      }
      _ <- resultQueue.awaitShutdown
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
        asd <- startDownload(work, results, state, peer, socket).forever
      } yield asd
    }

  private def startDownload(
    workQueue: Queue[Work],
    resultQueue: Queue[Result],
    state: PeerInfo,
    peer: PeerAddress,
    socket: MessageSocket
  ) =
    for {
      work <- workQueue.takeFilterM(w => state.hasPiece(peer, w.index))
      pieces <- downloadFullPiece(work, socket)
        .timeoutFail(TimeoutError)(2.minutes)
        .onError(_ => workQueue.offer(work))
      fullPiece = FullPiece.fromPieces(pieces)
      result    = Result(work, fullPiece)
      _ <- if (fullPiece.hash == work.hash) resultQueue.offer(result) else workQueue.offer(work)
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
              case piece: Piece => fulfilledRequests.update(_ :+ piece)
              case _ @Choke =>
                socket.readMessage
                  .repeatUntil(_ == Unchoke)
                  .zipRight(fulfilledRequests.get.map(_.size))
                  .flatMap { fullfilledLength =>
                    val leftoverRequests = requests.drop(fullfilledLength)
                    ZIO.foreach(leftoverRequests)(socket.writeMessage)
                  }
              case other => log.warn(s"Got different kind of message: $other")
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
      case Request(_, _, _) => ZIO.unit
      case Piece(_, _, _)   => ZIO.unit
      case Cancel(_, _, _)  => ZIO.unit
    }

  private def logged[R, E, A](peer: PeerAddress)(effect: ZIO[R, E, A]) =
    Logging.locally { ctx =>
      ctx
        .annotate(LogAnnotation.Name, peer.address.value :: Nil)
        .annotate(LogAnnotation.Timestamp, OffsetDateTime.now)
    }(effect)

  private def transducer(meta: Ref[TorrentMeta], queue: Queue[Result]) =
    ZTransducer[PieceIndex]
      .mapM(idx => meta.update(_.markCompleted(idx.value.toInt)))
      .mapM(_ => logProgress(meta))
      .mapM(_ => ZIO.ifM(meta.get.map(_.isComplete))(queue.shutdown, ZIO.unit))

  private def logProgress(meta: Ref[TorrentMeta]) =
    for {
      meta <- meta.get
      percent   = (meta.completed.toDouble / meta.bitfield.size) * 100
      formatted = "%.2f".format(percent)
      _ <- putStrLn(s"Completed $formatted% (${meta.completed} / ${meta.bitfield.size})")
    } yield ()

}
