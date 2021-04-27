package io.github.arainko.torrenties.domain.services

import io.github.arainko.torrenties._
import io.github.arainko.torrenties.domain.models.errors._
import io.github.arainko.torrenties.domain.models.state._
import zio._
import zio.console._
import zio.stream.ZTransducer

object Client {

  def start(
    metaRef: Ref[TorrentMeta]
  ): ZIO[Tracker with Merger with Downloader with Console, TrackerError, Unit] =
    for {
      meta <- metaRef.get
      torrentFile = meta.torrentFile
      announce    <- Tracker.announce(meta)
      workQueue   <- Queue.bounded[Work](torrentFile.pieceCount.toInt)
      resultQueue <- Queue.bounded[Result](torrentFile.pieceCount.toInt)
      _ <- Merger
        .daemon(torrentFile, resultQueue)
        .transduce(shutdownWatcher(metaRef, resultQueue))
        .runDrain
        .fork
      _         <- workQueue.offerAll(meta.incompleteWork)
      peerState <- PeerInfo.make(announce.peers, meta.incompleteWork.size.toLong)
      _         <- Downloader.daemon(torrentFile, workQueue, resultQueue, peerState)
      shutdown  <- resultQueue.awaitShutdown
    } yield shutdown

  private def shutdownWatcher(meta: Ref[TorrentMeta], queue: Queue[Result]) =
    ZTransducer[Result]
      .mapM(res => meta.update(_.markCompleted(res.work.index.toInt, res.work.length)))
      .mapM(_ => logProgress(meta))
      .mapM(_ => ZIO.ifM(meta.get.map(_.isComplete))(queue.shutdown, ZIO.unit))

  private def logProgress(meta: Ref[TorrentMeta]) =
    for {
      meta <- meta.get
      percent   = (meta.completedPieces.toDouble / meta.bitfield.size) * 100
      formatted = "%.2f".format(percent)
      _ <- putStrLn(s"Completed $formatted% (${meta.completedPieces} / ${meta.bitfield.size})")
    } yield ()

}
