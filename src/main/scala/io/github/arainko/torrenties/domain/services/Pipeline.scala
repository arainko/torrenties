package io.github.arainko.torrenties.domain.services

import io.github.arainko.torrenties.domain.models.state._
import io.github.arainko.torrenties.domain.syntax._
import zio._

object Pipeline {

  def fromMeta(meta: TorrentMeta): UIO[Pipeline] =
    for {
      work <- ZIO.succeed(meta.incompleteWork)
      size = if (work.size < 2) 2 else work.size
      workQueue   <- Queue.bounded[Work](size)
      resultQueue <- Queue.bounded[Result](size)
      _           <- workQueue.offerAll(work)
    } yield Pipeline(workQueue, resultQueue)

}

final case class Pipeline(work: Queue[Work], results: Queue[Result]) {
  def scheduleWork(workToSchedule: Work): UIO[Boolean] = work.offer(workToSchedule)

  def takeWork(pred: Work => UIO[Boolean]): UIO[Work] = work.takeFilterM(pred)

  def pushResult(result: Result): UIO[Boolean] = results.offer(result)

  def awaitShutdown: UIO[Unit] = results.awaitShutdown

  def shutdown: UIO[Unit] = results.shutdown
}
