package io.github.arainko.torrenties.domain.services

import zio.macros.accessible
import sttp.client3._
import sttp.client3.asynchttpclient.zio.AsyncHttpClientZioBackend
import io.github.arainko.torrenties.domain.model._
import zio._
import scodec.bits._
import io.github.arainko.torrenties.domain.model.Info.SingleFile
import io.github.arainko.torrenties.domain.model.Info.MultipleFile
import rainko.bencode.syntax._
import io.github.arainko.torrenties.infrastructure.codecs._
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import sttp.model.Uri
import sttp.model.QueryParams

@accessible
object Tracker {

  trait Service {
    def pingTracker(torrent: TorrentFile): Task[Unit]
  }

  val live: ULayer[Has[Service]] = ZLayer.succeed {
    new Service {

      private def urlEncoded(hash: ByteVector) =
        URLEncoder.encode(
          new String(hash.toArray, StandardCharsets.ISO_8859_1),
          StandardCharsets.ISO_8859_1
        )

      def pingTracker(torrent: TorrentFile): Task[Unit] =
        AsyncHttpClientZioBackend
          .managed()
          .use { backend =>
            val length = torrent.info match {
              case SingleFile(pieceLength, pieces, name, length)  => length
              case MultipleFile(pieceLength, pieces, name, files) => files.foldLeft(0L)(_ + _.length)
            }
            val announce   = torrent.announce
            val infoHash   = urlEncoded(torrent.info.infoHash)
            val peerId     = "-TT0000-k8hj0wgej6ch"
            val port       = "6885"
            val uploaded   = "0"
            val downloaded = "0"
            val corrupt    = "0"
            val compact    = "1"
            val event      = "started"
            val left       = s"$length"

            val params = Map(
              // "info_hash" -> infoHash,
              "peer_id"    -> peerId,
              "port"       -> port,
              "uploaded"   -> uploaded,
              "downloaded" -> downloaded,
              "corrupt"    -> corrupt,
              "compact"    -> compact,
              "event"      -> event,
              "left"       -> left
            )

            val paramsUrled = params
              .map { case (key, value) =>
                s"$key=$value"
              }
              .mkString("&")

            val url = s"$announce?$paramsUrled"
            println(url)

            val baseUri = uri"${torrent.announce}"
              .addQuerySegment(
                Uri.QuerySegment
                  .KeyValue("info_hash", infoHash, valueEncoding = identity)
              )
              .addParams(params)

            println(baseUri)

            val request = basicRequest
              .get(baseUri)
              .response(asString)

            backend
              .send(request)
              .flatMap(r => ZIO.fromEither(r.body.left.map(e => new Throwable(e))))
              .tap(resp => ZIO.succeed(println(resp)))
              .unit
          }
    }
  }
}
