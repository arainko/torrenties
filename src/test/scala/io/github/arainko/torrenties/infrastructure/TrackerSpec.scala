package io.github.arainko.torrenties.infrastructure

import zio._
import zio.duration._
import zio.test._
import zio.magic._
import zio.test.Assertion._
import zio.stream.SubscriptionRef
import zio.ZQueue
import zio.logging._
import zio.stream.ZStream
import zio.nio.channels._
import zio.clock.Clock
import zio.nio.core.file.Path
import zio.stream.ZSink
import java.nio.file.Paths
import java.nio.file.OpenOption
import java.nio.file.StandardOpenOption

object TrackerSpec extends DefaultRunnableSpec {

  private val opt: Set[OpenOption] = Set(StandardOpenOption.CREATE, StandardOpenOption.WRITE)

  private def writeToFile(queue: Queue[String]) =
    ZStream
      .fromQueue(queue)
      .mapM { res =>
        log.info(s"got $res") *>
        ZStream
          .fromChunk(Chunk.fromArray(res.getBytes))
          .run(ZSink.fromFile(Paths.get("testfile"), res.length().toLong, opt))
      }
      .runDrain

  def spec: ZSpec[Environment, Failure] =
    suite("costam")(
      testM("queue test") {
        for {
          q <- Queue.bounded[String](100)
          _ <- writeToFile(q).fork
          // _ <- q.offerAll("1" :: "  2" :: "   3" :: Nil)
          _ <- q.offer("2").repeat(Schedule.fixed(3.seconds)).inject(Clock.live)
        } yield assertCompletes
      }
    )
    .injectCustom(Logging.console())
      
}
