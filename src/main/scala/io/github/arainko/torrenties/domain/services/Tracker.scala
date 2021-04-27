package io.github.arainko.torrenties.domain.services

import cats.syntax.all._
import io.github.arainko.bencode.{Bencode, BencodeError}
import io.github.arainko.torrenties._
import io.github.arainko.torrenties.domain.codecs.bencode._
import io.github.arainko.torrenties.domain.models.errors._
import io.github.arainko.torrenties.domain.models.torrent.Info._
import io.github.arainko.torrenties.domain.models.torrent._
import io.github.arainko.torrenties.domain.models.state._
import scodec.bits._
import sttp.client3._
import sttp.client3.asynchttpclient.zio.SttpClient
import sttp.model.Uri
import zio._
import zio.macros.accessible

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@accessible
object Tracker {

  trait Service {
    def announce(torrent: TorrentMeta): IO[TrackerError, Announce]
  }

  val live: URLayer[SttpClient, Tracker] = ZLayer.fromService { backend =>
    new Service {

      private def urlEncoded(hash: ByteVector) =
        URLEncoder.encode(
          new String(hash.toArray, StandardCharsets.ISO_8859_1),
          StandardCharsets.ISO_8859_1
        )

      private def announceRequests(meta: TorrentMeta) = {
        val urls     = meta.torrentFile.httpAnnounces
        val infoHash = urlEncoded(meta.torrentFile.info.infoHash.value)
        val peerId   = urlEncoded(PeerId.default.value)
        val length = meta.torrentFile.info match {
          case SingleFile(pieceLength, pieces, name, length)  => length
          case MultipleFile(pieceLength, pieces, name, files) => files.foldLeft(0L)(_ + _.length)
        }
        val params = Map(
          "port"       -> "6881",
          "uploaded"   -> "0",
          "downloaded" -> "0",
          "corrupt"    -> "0",
          "compact"    -> "1",
          "event"      -> "started",
          "left"       -> s"${length - meta.completedBytes}"
        )
        urls
          .map { announce =>
            uri"${meta.torrentFile.announce}"
              .addQuerySegment(
                Uri.QuerySegment.KeyValue("info_hash", infoHash, valueEncoding = identity)
              )
              .addQuerySegment(
                Uri.QuerySegment.KeyValue("peer_id", peerId, valueEncoding = identity)
              )
              .addParams(params)
          }
          .map(uri =>
            basicRequest
              .get(uri)
              .response(asByteArrayAlways)
              .mapResponse(ByteVector.view)
              .mapResponse(Bencode.parse(_))
          )
      }

      def announce(torrent: TorrentMeta): IO[TrackerError, Announce] =
        announceRequests(torrent)
          .map { request =>
            for {
              bencode <- backend
                .send(request)
                .map(_.body)
                .mapError(e => TrackerError.TrackerFailure(e.getMessage))
                .flatMap(r => ZIO.fromEither(r.leftMap(e => TrackerError.MalformedBencode(e.message))))
              decoded <- ZIO.fromEither(bencode.cursor.as[Announce]).mapError {
                case BencodeError.UnexpectedValue(msg) => TrackerError.MalformedBencode(msg)
              }
            } yield decoded
          }
          .reduceLeftOption(_.orElse(_))
          .getOrElse(ZIO.fail(TrackerError.NoHttpAnnounces))
    }
  }
}
