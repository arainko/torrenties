package io.github.arainko.torrenties

import io.github.arainko.bencode.Bencode
import io.github.arainko.torrenties.domain.codecs.bencode._
import io.github.arainko.torrenties.domain.models.errors._
import io.github.arainko.torrenties.domain.models.torrent._
import io.github.arainko.torrenties.domain.services.{Client, _}
import scodec.bits.ByteVector
import sttp.client3.asynchttpclient.zio.AsyncHttpClientZioBackend
import zio._
import zio.logging._
import zio.magic._
import zio.stream._

object Main extends App {

  private val logging = Logging.console(LogLevel.Debug)

  private val program =
    for {
      torrentBytes <- ZStream.fromResource("ubuntu.torrent").runCollect.map(_.toArray).map(ByteVector.apply)
      parsed = Bencode.parseAs[TorrentFile](torrentBytes)
      torrent <- ZIO.fromEither(parsed).mapError(SerializationError.fromBencodeError)
      _       <- Client.start(torrent)
    } yield ()

  def run(args: List[String]): URIO[ZEnv, ExitCode] =
    program.exitCode
      .injectCustom(
        Tracker.live,
        AsyncHttpClientZioBackend.layer().orDie,
        logging
      )

}
