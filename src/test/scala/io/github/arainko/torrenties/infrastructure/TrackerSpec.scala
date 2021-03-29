package io.github.arainko.torrenties.infrastructure

import io.github.arainko.bencode._
import io.github.arainko.torrenties.domain.codecs.bencode._
import io.github.arainko.torrenties.domain.models.torrent._
import io.github.arainko.torrenties.domain.services.{Client, Tracker}
import scodec.bits.ByteVector
import sttp.client3.asynchttpclient.zio._
import zio.magic._
import zio.stream.ZStream
import zio.test.{DefaultRunnableSpec, ZSpec, _}
import zio.{ZIO, duration}
import zio.logging.Logging
import zio.logging._

object TrackerSpec extends DefaultRunnableSpec {

  def spec: ZSpec[Environment, Failure] =
    suite("costam")(
      testM("asd") {
        for {
          torrentFile <- ZStream.fromResource("ubuntu.torrent").runCollect.map(_.toArray).map(ByteVector.apply)
          parsed      <- ZIO.fromEither(Bencode.parse(torrentFile))
          torrent     <- ZIO.fromEither(parsed.cursor.as[TorrentFile])
          // response    <- Tracker.announce(torrent)
          _ <- Client.start(torrent)
          // _ <- ZIO.foreach(fibers)(_.join)
          _ <- ZIO.sleep(duration.`package`.Duration.Infinity)
        } yield assertCompletes
      }
    ).provideCustomMagicLayer(
      Logging.console(LogLevel.Debug),
      Tracker.live,
      AsyncHttpClientZioBackend.layer().orDie
    )
}
