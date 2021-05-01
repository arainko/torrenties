package io.github.arainko.torrenties.infrastructure

import io.github.arainko.bencode.Bencode
import io.github.arainko.torrenties.domain.codecs.bencode._
import io.github.arainko.torrenties.domain.models.network._
import io.github.arainko.torrenties.domain.models.state._
import io.github.arainko.torrenties.domain.models.torrent._
import io.github.arainko.torrenties.domain.services._
import scodec.bits.ByteVector
import zio._
import zio.clock.Clock
import zio.duration._
import zio.logging._
import zio.magic._
import zio.stream.ZStream
import zio.test._
import zio.config.typesafe.TypesafeConfig
import io.github.arainko.torrenties.config._

object TrackerSpec extends DefaultRunnableSpec {

  private val folderConfig = TypesafeConfig.fromDefaultLoader(FolderConfig.descriptor)

  def client =
    MessageSocket(
      PeerAddress(
        Segment(127),
        Segment(0),
        Segment(0),
        Segment(1),
        Port(6881)
      )
    )

  def spec: ZSpec[Environment, Failure] =
    suite("costam")(
      testM("queue test") {
        for {
          torrentFile <- ZStream.fromResource("debian.torrent")
          .runCollect.map(_.toArray)
          .map(ByteVector.apply)
          parsed <- ZIO.fromEither(Bencode.parseAs[TorrentFile](torrentFile))
          meta = TorrentMeta.empty(parsed)
          session <- Session.fromMeta(meta)
          _ <- Server.start(6881, session).useNow.fork
          _ <- ZIO.sleep(3.seconds).provideLayer(Clock.live)
          _ <- client.use { socket =>
            socket.initializeHandshake(parsed) *> socket.readMessage.forever
          }
        } yield assertCompletes
      }.injectCustom(Logging.console(LogLevel.Debug), folderConfig.orDie)
    )

}
