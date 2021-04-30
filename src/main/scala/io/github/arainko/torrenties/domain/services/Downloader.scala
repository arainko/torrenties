package io.github.arainko.torrenties.domain.services

import io.github.arainko.torrenties._
import io.github.arainko.torrenties.domain.models.errors._
import io.github.arainko.torrenties.domain.models.network.PeerMessage._
import io.github.arainko.torrenties.domain.models.network.{PeerMessage, _}
import io.github.arainko.torrenties.domain.models.state._
import io.github.arainko.torrenties.domain.models.torrent._
import zio._
import zio.clock.Clock
import zio.duration._
import zio.logging.{LogAnnotation, Logging, _}
import zio.macros.accessible
import zio.stream.ZStream

import java.time.OffsetDateTime

@accessible
object Downloader {

  trait Service {

    def daemon(
      session: Session,
      pipeline: Pipeline
    ): IO[TrackerError, Unit]
  }

  val live: URLayer[Tracker with Logging with Clock, Downloader] = ZLayer.fromFunction { env =>
    new Service {

      def daemon(
        session: Session,
        pipeline: Pipeline
      ): IO[TrackerError, Unit] = {
        for {
          announce <- Tracker.announce(session.initialMeta)
          peerInfo <- PeerInfo.make(announce.peers, session.torrent.pieceCount)
          peers    <- peerInfo.peers
          workers = peers
            .map(MessageSocket.apply)
            .map(_.use(worker(session.torrent, pipeline, peerInfo)))
          _     <- ZIO.forkAll_(workers)
          _     <- workerDaemon(announce, peerInfo, session, pipeline).fork
          await <- pipeline.awaitShutdown
        } yield await
      }.provide(env)

      private def workerDaemon(
        announce: Announce,
        state: PeerInfo,
        session: Session,
        pipeline: Pipeline
      ) = {
        val reannounceAfterInterval =
          for {
            _           <- ZIO.sleep(announce.interval)
            _           <- log.info(s"Reannouncing after interval")
            currentMeta <- session.currentMeta
            currentDiff <- session.diff
            reannounce  <- Tracker.reannounce(currentMeta, currentDiff)
          } yield reannounce

        val reannounceWhenLacksPeers =
          for {
            _ <- state.peers.map(_.size).repeat {
              Schedule.recurWhile[Int](_ > 15) && Schedule.fixed(announce.interval.dividedBy(3))
            }
            _           <- log.info(s"Reannouncing after lack of peers")
            currentMeta <- session.currentMeta
            currentDiff <- session.diff
            reannounce  <- Tracker.reannounce(currentMeta, currentDiff)
          } yield reannounce

        reannounceAfterInterval
          .race(reannounceWhenLacksPeers)
          .map(_.peers)
          .flatMap(state ++ _)
          .map(_.map(peer => MessageSocket(peer).use(worker(session.torrent, pipeline, state))))
          .flatMap(ZIO.forkAll_(_))
          .forever
      }

      private def worker(
        torrentFile: TorrentFile,
        pipeline: Pipeline,
        state: PeerInfo
      )(socket: MessageSocket) =
        logged(socket.peer) {
          for {
            _ <- socket.handshake(torrentFile)
            _ <- socket.writeMessage(Interested)
            _ <- socket.readMessage
              .flatMap(msg => updatePeerState(msg, state, socket).as(msg))
              .repeatUntil(_ == Unchoke)
              .timeoutFail(TimeoutError)(3.minutes)
            never <- startDownload(pipeline, state, socket.peer, socket).forever
          } yield never
        }.onError(_ => state - socket.peer)

      private def startDownload(
        pipeline: Pipeline,
        state: PeerInfo,
        peer: PeerAddress,
        socket: MessageSocket
      ) =
        for {
          work <- pipeline.takeWork(w => state.hasPiece(peer, w.index))
          pieces <- downloadFullPiece(work, socket)
            .timeoutFail(TimeoutError)(2.minutes)
            .onError(_ => pipeline.scheduleWork(work))
          fullPiece = FullPiece.fromPieces(pieces)
          result    = Result(work, fullPiece)
          _ <- if (fullPiece.hash == work.hash) pipeline.pushResult(result) else pipeline.scheduleWork(work)
        } yield fullPiece

      private def downloadFullPiece(work: Work, socket: MessageSocket) =
        ZStream
          .fromIterable(work.requests)
          .chunkN(5)
          .mapChunksM(handleRequests(socket))
          .runCollect

      private def handleRequests(socket: MessageSocket)(requests: Chunk[Request]) =
        ZIO.foreach(requests)(socket.writeMessage) *>
          ZStream
            .unfoldM(0) { fulfilledRequests =>
              if (fulfilledRequests == requests.size) ZIO.none
              else
                socket.readMessage.flatMap {
                  case piece: Piece => ZIO.some(Some(piece) -> (fulfilledRequests + 1))
                  case _ @Choke =>
                    awaitUnchoke(socket, requests, fulfilledRequests) *>
                      ZIO.some(None -> fulfilledRequests)
                  case other =>
                    log.warn(s"Got different kind of message: $other") *>
                      ZIO.some(None -> fulfilledRequests)
                }
            }
            .collect { case Some(piece) => piece }
            .runCollect

      private def awaitUnchoke(socket: MessageSocket, requests: Chunk[Request], completed: Int) =
        socket.readMessage
          .repeatUntil(_ == Unchoke)
          .zipRight {
            val leftoverRequests = requests.drop(completed)
            ZIO.foreach(leftoverRequests)(socket.writeMessage)
          }

      private def updatePeerState(
        message: PeerMessage,
        state: PeerInfo,
        socket: MessageSocket
      ) =
        message match {
          case KeepAlive =>
            socket.writeMessage(KeepAlive)
          case Choke =>
            state.update[ChokeState](socket.peer, ChokeState.Choked)
          case Unchoke =>
            state.update[ChokeState](socket.peer, ChokeState.Unchoked)
          case Interested =>
            state.update[InterestState](socket.peer, InterestState.Interested)
          case NotInterested =>
            state.update[InterestState](socket.peer, InterestState.NotInterested)
          case Have(pieceIndex) =>
            state.updateBitfield(socket.peer, pieceIndex.value, true)
          case Bitfield(payload) =>
            state.update[StateBitfield](socket.peer, StateBitfield(payload))
          case _ => ZIO.unit
        }

      private def logged[R, E, A](peer: PeerAddress)(effect: ZIO[R, E, A]) =
        Logging.locally { ctx =>
          ctx
            .annotate(LogAnnotation.Name, peer.address.value :: Nil)
            .annotate(LogAnnotation.Timestamp, OffsetDateTime.now)
        }(effect)

    }
  }
}
