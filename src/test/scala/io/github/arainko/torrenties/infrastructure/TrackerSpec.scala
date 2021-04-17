package io.github.arainko.torrenties.infrastructure

import zio._
import zio.clock.Clock
import zio.duration._
import zio.magic._
import zio.test._

object TrackerSpec extends DefaultRunnableSpec {

  def spec: ZSpec[Environment, Failure] =
    suite("costam")(
      testM("queue test") {
        for {
          q <- Queue.bounded[String](100)
          // _ <- writeToFile(q).fork
          _ <- q.offer("2").repeat(Schedule.fixed(3.seconds)).inject(Clock.live)
        } yield assertCompletes
      }
    )
  // .injectCustom(Logging.console())

}
