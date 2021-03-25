package io.github.arainko.torrenties.infrastructure

import io.github.arainko.bencode._
import io.github.arainko.torrenties.domain.codecs.bencode._
import io.github.arainko.torrenties.domain.models.torrent._
import io.github.arainko.torrenties.domain.services.{Peerer, Tracker}
import scodec.bits.ByteVector
import sttp.client3.asynchttpclient.zio._
import zio.ZIO
import zio.magic._
import zio.stream.ZStream
import zio.test.{DefaultRunnableSpec, ZSpec, _}
import io.github.arainko.torrenties.domain.models.network._
import java.time.Duration
import zio.duration

object TrackerSpec extends DefaultRunnableSpec {

  def spec: ZSpec[Environment, Failure] =
    suite("costam")(
      testM("asd") {
        for {
          torrentFile <- ZStream.fromResource("debian.torrent").runCollect.map(_.toArray).map(ByteVector.apply)
          parsed      <- ZIO.fromEither(Bencode.parse(torrentFile))
          torrent     <- ZIO.fromEither(parsed.cursor.as[TorrentFile])
          response    <- Tracker.announce(torrent)
          fibers <- ZIO.foreach(response.peers) { peer =>
            Peerer.handshake(torrent, peer).fork
          }
          // _ <- ZIO.foreach(fibers)(_.join)
          _ <- ZIO.sleep(duration.`package`.Duration.Infinity)
        } yield assertCompletes
      }
    ).provideCustomMagicLayer(
      Tracker.live,
      AsyncHttpClientZioBackend.layer().orDie
    )

  // private def communicationLoop(socket: Socket[Task]) =
  //   for {
  //     length <- socket.reads(4, 150.seconds.some).through(StreamDecoder.once(uint32).toPipeByte)
  //     _ = println(s"Message of length $length incoming")
  //     message <-
  //       if (length == 0) Stream(PeerMessage.KeepAlive).covary[Task]
  //       else
  //         socket
  //           .reads(1)
  //           .through(StreamDecoder.once(byte).map(_.toInt).map(PeerMessageId).toPipeByte)
  //           .map(PeerMessage.Meaningful.fromId)
  //     _           = println(s"Message is $message, length: $length")
  //     payloadSize = if (length == 0) 0 else length.toInt - 1
  //     readThru <- socket
  //       .reads(payloadSize)
  //       .through(StreamDecoder.once(bytes(payloadSize)).toPipeByte)
  //       .evalTapChunk(chunk => ZIO.succeed(println(s"$chunk")))

  //   } yield (message, length)

}
