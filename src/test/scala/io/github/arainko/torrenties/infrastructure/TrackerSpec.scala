package io.github.arainko.torrenties.infrastructure

import zio._
import zio.test._
import zio.magic._
import zio.test.Assertion._
import zio.stream.SubscriptionRef
import zio.ZQueue
import zio.logging._
import zio.stream.ZStream

object TrackerSpec extends DefaultRunnableSpec {

  private def takeEquals(q: Queue[Int], eq: Int): UIO[Int] = 
    q.take.flatMap { taken =>
      ZIO.ifM(ZIO.succeed(taken == eq))(
        ZIO.succeed(taken),
        q.offer(taken) *> takeEquals(q, eq)
      )
    }

  def spec: ZSpec[Environment, Failure] =
    suite("costam")(
      testM("queue test") {
        assertCompletesM
      }
    )
}
