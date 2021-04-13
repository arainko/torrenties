package io.github.arainko.torrenties.domain.services

import io.github.arainko.torrenties.domain.models.network.PeerMessage._
import io.github.arainko.torrenties.domain.models.network._
import io.github.arainko.torrenties.domain.models.state._
import io.github.arainko.torrenties.domain.models.torrent._
import zio._
import zio.duration._
import zio.logging.{LogAnnotation, Logging, _}
import zio.stream.ZStream

import java.time.OffsetDateTime
import zio.clock.Clock
import scala.annotation.nowarn
import zio.stream.ZSink
import java.nio.file.Paths
import zio.nio.channels.AsynchronousFileChannel
import zio.nio.core.file.Path

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
    state: PeerInfo
  ) =
    MessageSocket(peer).use { socket =>
      for {
        _ <- socket.handshake(torrentFile)
        _ <- socket.writeMessage(Interested)
        _ <- socket.readMessage
          .flatMap(m => dispatch(m, peer, state, socket).as(m))
          .repeatUntil(_ == Unchoke)
          .tap(_ => log.debug(s"Unchoked, requesting a piece..."))
        asd <- startDownload(work, state, peer, socket).forever
      } yield asd
    }

  private def takeIfHas(q: Queue[Work], predM: Work => UIO[Boolean]): URIO[Clock, Work] =
    q.take.flatMap { taken =>
      ZIO.ifM(predM(taken))(
        ZIO.succeed(taken),
        q.offer(taken) *> ZIO.sleep(50.millis) *> takeIfHas(q, predM)
      )
    }

  private def saveToFile(work: Work, fullPiece: FullPiece) =
    ZStream
      .fromChunk(Chunk.fromArray(fullPiece.bytes.toArray))
      .run(ZSink.fromFile(Paths.get(s"download_ubuntu/work-${work.index}")))
      .unit

  private def startDownload(
    workQueue: Queue[Work],
    state: PeerInfo,
    peer: PeerAddress,
    socket: MessageSocket
  ) =
    for {
      work   <- takeIfHas(workQueue, w => state.hasPiece(peer, w.index))
      pieces <- downloadFullPiece(work, socket).onError(_ => workQueue.offer(work))
      fullPiece = FullPiece.fromPieces(pieces)
      _ <- if (fullPiece.hash == work.hash) saveToFile(work, fullPiece) else workQueue.offer(work)
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
