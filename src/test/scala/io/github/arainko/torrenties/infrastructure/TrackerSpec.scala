package io.github.arainko.torrenties.infrastructure

import zio._
import zio.clock.Clock
import zio.duration._
import zio.magic._
import zio.test._
import io.github.arainko.torrenties.domain.services.Server
import zio.stream.ZStream
import zio.console._
import zio.nio.channels.AsynchronousSocketChannel
import io.github.arainko.torrenties.domain.services.MessageSocket
import io.github.arainko.torrenties.domain.models.network
import zio.logging.Logging
import zio.logging.LogLevel
import zio.logging.log

object TrackerSpec extends DefaultRunnableSpec {

  def client =
    MessageSocket(
      network.PeerAddress(
        network.Segment(127),
        network.Segment(0),
        network.Segment(0),
        network.Segment(1),
        network.Port(6881)
      )
    )

  def spec: ZSpec[Environment, Failure] =
    suite("costam")(
      // testM("queue test") {
      //   for {
      //     q <- Server.start(6881)
      //       .mapM(socket => log.debug(s"Connected!").as(socket))
      //       .mapM(_.readChunk(4096).tap(chunk => log.debug(s"$chunk")).forever.fork)
      //       .runDrain
      //       .fork
      //       _ <- ZIO.sleep(3.seconds).provideLayer(Clock.live)
      //     _ <- client.use(
      //       _.writeMessage(network.PeerMessage.Choke)
      //       .repeat(Schedule.fixed(3.seconds))
      //       .provideLayer(Clock.live)
      //       )
      //       // .provideLayer(Console.live)
      //     // _ <- writeToFile(q).fork

      //   } yield assertCompletes
      // }
      testM("schedule test") {
        for {
          counter <- Ref.make(25)
          _ <- counter
            .update { value =>
              println(s"Decrementing $value")
              value - 1
            }
            .repeat(Schedule.fixed(1.seconds))
            .fork
          printUntil10 = counter.get
            .tap(value => putStrLn(s"Got $value"))
            .repeat((Schedule.recurWhile[Int](_ > 15) && Schedule.fixed(5.seconds))) *> 
              counter.set(50) *>
              putStrLn(s"Counter set to 50 cause value was < 15")

          printEvery10s =
          ZIO.sleep(20.seconds) *> counter.set(25) *> putStrLn("Counter set to 25 cause time has elapsed")
          _ <- printUntil10.race(printEvery10s).forever
        } yield assertCompletes
      }.provideLayer(ZEnv.live)
    )

}
