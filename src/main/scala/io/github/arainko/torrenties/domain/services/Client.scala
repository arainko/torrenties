package io.github.arainko.torrenties.domain.services

import io.github.arainko.torrenties._
import io.github.arainko.torrenties.domain.models.errors._
import io.github.arainko.torrenties.domain.models.state._
import zio._
import zio.console._
import zio.stream.ZTransducer

object Client {

  def start(
    session: Session
  ): ZIO[Tracker with Merger with Downloader with Console, TrackerError, Unit] =
    for {
      pipeline <- Pipeline.fromMeta(session.initialMeta)
      _ <- Merger
        .daemon(session.torrent, pipeline.results)
        .transduce(shutdownWatcher(session, pipeline))
        .runDrain
        .fork
      _         <- Downloader.daemon(session, pipeline)
      shutdown  <- pipeline.awaitShutdown
    } yield shutdown

  private def shutdownWatcher(session: Session, pipeline: Pipeline) =
    ZTransducer[Result]
      .mapM(res => session.markCompleted(res.work.index.toInt, res.work.length))
      .mapM(_ => logProgress(session))
      .mapM(_ => ZIO.ifM(session.isComplete)(pipeline.shutdown, ZIO.unit))

  private def logProgress(session: Session) =
    for {
      meta <- session.currentMeta
      percent   = (meta.completedPieces.toDouble / meta.bitfield.size) * 100
      formatted = "%.2f".format(percent)
      _ <- putStrLn(s"Completed $formatted% (${meta.completedPieces} / ${meta.bitfield.size})")
    } yield ()

}
