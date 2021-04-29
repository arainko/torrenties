package io.github.arainko.torrenties.domain.services

import io.github.arainko.torrenties._
import io.github.arainko.torrenties.domain.models.errors._
import io.github.arainko.torrenties.domain.models.network.PeerMessage._
import io.github.arainko.torrenties.domain.models.network.{PeerMessage, _}
import io.github.arainko.torrenties.domain.models.state._
import io.github.arainko.torrenties.domain.models.torrent._
import io.github.arainko.torrenties.domain.syntax._
import zio.clock.Clock
import zio.duration._
import zio.logging.{LogAnnotation, Logging, _}
import zio.macros.accessible
import zio.stream.ZStream
import zio.{Queue, _}

import java.time.OffsetDateTime
import scala.annotation.nowarn

@accessible
object Downloader {

  trait Service {

    def daemon(
      meta: Ref[TorrentMeta],
      workQueue: Queue[Work],
      resultQueue: Queue[Result]
    ): IO[TrackerError, Unit]
  }

  val live: URLayer[Tracker with Logging with Clock, Downloader] = ZLayer.fromFunction { env =>
    new Service {
      def daemon(
        meta: Ref[TorrentMeta],
        workQueue: Queue[Work],
        resultQueue: Queue[Result]
      ): IO[TrackerError, Unit] = {
        for {
          initialMeta <- meta.get
          announce    <- Tracker.announce(initialMeta)
          peerInfo    <- PeerInfo.make(announce.peers, initialMeta.torrentFile.pieceCount)
          peers       <- peerInfo.state.get.map(_.keySet)
          workers = peers
            .map(MessageSocket.apply)
            .map(_.use(worker(initialMeta.torrentFile, workQueue, resultQueue, peerInfo)))
          _     <- ZIO.forkAll_(workers)
          _     <- workerDaemon(announce, peerInfo, meta, initialMeta, workQueue, resultQueue).fork
          await <- resultQueue.awaitShutdown
        } yield await
      }.provide(env)

      private def workerDaemon(
        announce: Announce,
        state: PeerInfo,
        metaRef: Ref[TorrentMeta],
        initialMeta: TorrentMeta,
        workQueue: Queue[Work],
        resultQueue: Queue[Result]
      ) =
        for {
          meta <- metaRef.get
          sessionDiff = metaRef.map(_.diff(initialMeta))
          peersRef    = state.state.map(_.keySet)
          reannounceAfterInterval =
            for {
              _           <- ZIO.sleep(announce.interval)
              _           <- log.info(s"Reannouncing after interval")
              currentMeta <- metaRef.get
              currentDiff <- sessionDiff.get
              reannounce  <- Tracker.reannounce(currentMeta, currentDiff)
            } yield reannounce
          reannounceWhenLacksPeers =
            for {
              _ <- peersRef.get.map(_.size).repeat {
                Schedule.recurWhile[Int](_ > 15) && Schedule.fixed(announce.interval.dividedBy(3))
              }
              _           <- log.info(s"Reannouncing after lack of peers")
              currentMeta <- metaRef.get
              currentDiff <- sessionDiff.get
              reannounce  <- Tracker.reannounce(currentMeta, currentDiff)
            } yield reannounce
          daemon <- reannounceAfterInterval
            .race(reannounceWhenLacksPeers)
            .map(_.peers)
            .flatMap(state.addPeers)
            .map(_.map(peer => MessageSocket(peer).use(worker(initialMeta.torrentFile, workQueue, resultQueue, state))))
            .flatMap(ZIO.forkAll_(_))
            .forever
        } yield daemon

      private def worker(
        torrentFile: TorrentFile,
        work: Queue[Work],
        results: Queue[Result],
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
            never <- startDownload(work, results, state, socket.peer, socket).forever
          } yield never
        }.onError(_ => state.removePeer(socket.peer))

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
            state.updatePeerChoke(socket.peer, ChokeState.Choked)
          case Unchoke =>
            state.updatePeerChoke(socket.peer, ChokeState.Unchoked)
          case Interested =>
            state.updatePeerInterest(socket.peer, InterestState.Interested)
          case NotInterested =>
            state.updatePeerInterest(socket.peer, InterestState.NotInterested)
          case Have(pieceIndex) =>
            state.updateBitfield(socket.peer, pieceIndex.value, true)
          case Bitfield(payload) =>
            state.setBitfield(socket.peer, payload)
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
