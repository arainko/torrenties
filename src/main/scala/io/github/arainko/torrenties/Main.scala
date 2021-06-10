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

  private def torrentFile(path: String) =
    ZStream
      .fromResource(path)
      .runCollect
      .map(_.toArray)
      .map(ByteVector.apply)
      .map(Bencode.parseAs[TorrentFile](_))
      .flatMap(ZIO.fromEither(_).mapError(SerializationError.fromBencodeError))

  private def program(args: List[String]) =
    for {
      path    <- ZIO.getOrFailWith(NotEnoughArgumentsError)(args.headOption)
      torrent <- torrentFile(path)
      meta    <- Merger.meta(torrent)
      session <- Session.fromMeta(meta)
      _       <- Server.start(6881, session).useNow.fork
      _       <- if (meta.isNotComplete) Client.start(session) else ZIO.unit
    } yield ()

  def run(args: List[String]): URIO[ZEnv, ExitCode] =
    program(args).exitCode
      .injectCustom(
        Tracker.live,
        Merger.live,
        Downloader.live,
        AsyncHttpClientZioBackend.layer().orDie,
        folderConfig.orDie,
        logging
      )
}
