package io.github.arainko.torrenties

import io.github.arainko.bencode.Bencode
import io.github.arainko.torrenties.config.FolderConfig
import io.github.arainko.torrenties.domain.codecs.bencode._
import io.github.arainko.torrenties.domain.models.errors._
import io.github.arainko.torrenties.domain.models.torrent._
import io.github.arainko.torrenties.domain.services._
import scodec.bits.ByteVector
import sttp.client3.asynchttpclient.zio.AsyncHttpClientZioBackend
import zio._
import zio.config.typesafe._
import zio.logging._
import zio.magic._
import zio.stream._

object Main extends App {

  private val logging = Logging.console(LogLevel.Info)

  private val folderConfig = TypesafeConfig.fromDefaultLoader(FolderConfig.descriptor)

  private val program =
    for {
      torrentBytes <- ZStream.fromResource("debian.torrent").runCollect.map(_.toArray).map(ByteVector.apply)
      parsed = Bencode.parseAs[TorrentFile](torrentBytes)
      torrent <- ZIO.fromEither(parsed).mapError(SerializationError.fromBencodeError)
      meta    <- Merger.meta(torrent)
      _       <- if (meta.isNotComplete) Ref.make(meta).flatMap(Client.start) else ZIO.unit
    } yield ()

  def run(args: List[String]): URIO[ZEnv, ExitCode] =
    program.exitCode
      .injectCustom(
        Tracker.live,
        Merger.live,
        Downloader.live,
        AsyncHttpClientZioBackend.layer().orDie,
        folderConfig.orDie,
        logging
      )
}
