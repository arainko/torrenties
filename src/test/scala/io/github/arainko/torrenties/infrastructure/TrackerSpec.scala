package io.github.arainko.torrenties.infrastructure

import cats.effect._
import cats.syntax.all._
import io.github.arainko.bencode._
import io.github.arainko.torrenties.domain.codecs.Binary
import io.github.arainko.torrenties.domain.codecs.bencode._
import io.github.arainko.torrenties.domain.models.network._
import io.github.arainko.torrenties.domain.models.torrent._
import io.github.arainko.torrenties.domain.services.Tracker
import fs2._
import fs2.io.tcp.Socket
import scodec.bits.ByteVector
import scodec.codecs._
import scodec.stream._
import zio.interop.catz._
import zio.stream.ZStream
import zio.test.{DefaultRunnableSpec, ZSpec, _}
import zio.{Task, ZIO}
import zio.magic._
import sttp.client3.asynchttpclient.zio._

import java.net.InetSocketAddress
import scala.concurrent.duration._
import fs2.io.tcp.SocketGroup

object TrackerSpec extends DefaultRunnableSpec {

  private val blocker = Blocker[Task]

  def spec: ZSpec[Environment, Failure] =
    suite("costam")(
      testM("asd") {
        for {
          torrentFile <- ZStream.fromResource("debian.torrent").runCollect.map(_.toArray).map(ByteVector.apply)
          parsed      <- ZIO.fromEither(Bencode.parse(torrentFile))
          torrent     <- ZIO.fromEither(parsed.cursor.as[TorrentFile])
          response    <- Tracker.announce(torrent)
          peer = response.peers(0)
          // _ <- fs2PeerHandshake(torrent, peer).either
          // _ <- fs2PeerHandshake(torrent, peer).either
          socket = blocker.flatMap(SocketGroup[Task](_))
          fibers <- ZIO.foreach(response.peers) { peer =>
            val address = new InetSocketAddress(peer.address.value, peer.port.value)
            val client  = socket.flatMap(_.client[Task](address))
            client.use { socket =>
              (handshake(socket, torrent) *>
                communicationLoop(socket).repeat).compile.drain.either
                .tap(res => ZIO(println(res)))
            }.fork
          }
          _ <- ZIO.foreach(fibers)(_.join)
        } yield assertCompletes
      }
    ).provideCustomMagicLayer(
      Tracker.live,
      AsyncHttpClientZioBackend.layer().orDie
    )

  private def handshake(socket: Socket[Task], torrentFile: TorrentFile) = {
    val handshake = Handshake.withDefualts(torrentFile.info.infoHash, PeerId.default)
    for {
      _ <-
       Stream(handshake)
        .covary[Task]
        .through(StreamEncoder.once(Binary.handshake).toPipeByte)
        .through(socket.writes())
      response <- socket.reads(68).through(StreamDecoder.once(Binary.handshake).toPipeByte)
      _ = println(s"Handshake is $handshake")
    } yield response
  }

  private def communicationLoop(socket: Socket[Task]) =
    for {
      length <- socket.reads(4, 150.seconds.some).through(StreamDecoder.once(uint32).toPipeByte)
      _ = println(s"Message of length $length incoming")
      message <-
        if (length == 0) Stream(PeerMessage.KeepAlive).covary[Task]
        else
          socket
            .reads(1)
            .through(StreamDecoder.once(byte).map(_.toInt).map(PeerMessageId).toPipeByte)
            .map(PeerMessage.Meaningful.fromId)
      _           = println(s"Message is $message, length: $length")
      payloadSize = if (length == 0) 0 else length.toInt - 1
      readThru <- socket
        .reads(payloadSize)
        .through(StreamDecoder.once(bytes(payloadSize)).toPipeByte)
        .evalTapChunk(chunk => ZIO.succeed(println(s"$chunk")))

    } yield (message, length)

}
