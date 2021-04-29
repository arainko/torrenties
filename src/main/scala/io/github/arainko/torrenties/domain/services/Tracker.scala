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
    def reannounce(torrent: TorrentMeta, sessionDiff: SessionDiff): IO[TrackerError, Announce]
  }

  val live: URLayer[SttpClient, Tracker] = ZLayer.fromService { backend =>
    new Service {

      def announce(torrent: TorrentMeta): IO[TrackerError, Announce] = trackerRequest(torrent, announceParamas(torrent))

      def reannounce(torrent: TorrentMeta, sessionDiff: SessionDiff): IO[TrackerError, Announce] =
        trackerRequest(torrent, reannounceParams(torrent, sessionDiff))

      private def trackerRequest(torrent: TorrentMeta, params: Map[String, String]) =
        announceRequests(torrent, announceParamas(torrent))
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

      private def announceRequests(meta: TorrentMeta, params: Map[String, String]) = {
        val urls     = meta.torrentFile.httpAnnounces
        val infoHash = urlEncoded(meta.torrentFile.info.infoHash.value)
        val peerId   = urlEncoded(PeerId.default.value)

        urls
          .map { announce =>
            uri"$announce"
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

      private def announceParamas(meta: TorrentMeta) = {
        val length = meta.torrentFile.info match {
          case SingleFile(pieceLength, pieces, name, length)  => length
          case MultipleFile(pieceLength, pieces, name, files) => files.foldLeft(0L)(_ + _.length)
        }
        Map(
          "port"       -> "6881",
          "uploaded"   -> "0",
          "downloaded" -> "0",
          "corrupt"    -> "0",
          "compact"    -> "1",
          "event"      -> "started",
          "left"       -> s"${length - meta.completedBytes}"
        )
      }

      private def reannounceParams(meta: TorrentMeta, diff: SessionDiff) = {
        val length = meta.torrentFile.info match {
          case SingleFile(pieceLength, pieces, name, length)  => length
          case MultipleFile(pieceLength, pieces, name, files) => files.foldLeft(0L)(_ + _.length)
        }
        Map(
          "port"       -> "6881",
          "uploaded"   -> s"${diff.uploadedDiff}",
          "downloaded" -> s"${diff.downloadedDiff}",
          "corrupt"    -> "0",
          "compact"    -> "1",
          "left"       -> s"${length - meta.completedBytes}"
        )
      }

      private def urlEncoded(hash: ByteVector) =
        URLEncoder.encode(
          new String(hash.toArray, StandardCharsets.ISO_8859_1),
          StandardCharsets.ISO_8859_1
        )
    }
  }
}
