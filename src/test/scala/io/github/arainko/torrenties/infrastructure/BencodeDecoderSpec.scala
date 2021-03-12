package io.github.arainko.torrenties.infrastructure

import io.github.arainko.torrenties.domain.model._
import rainko.bencode._
import rainko.bencode.syntax._
import scodec.bits.ByteVector
import zio.test._
import zio.test.Assertion._
import zio.stream.ZStream
import zio.ZIO
import java.nio.charset.StandardCharsets
import io.github.arainko.torrenties.domain.services.Tracker

object BencodeDecoderSpec extends DefaultRunnableSpec {
  import io.github.arainko.torrenties.infrastructure.codecs._

  private val singleFileInfo =
    Bencode.fromFields(
      "piece length" -> Bencode.fromInt(20),
      "pieces"       -> Bencode.fromByteVector(ByteVector.fill(20000)(5)),
      "name"         -> Bencode.fromString("filename"),
      "length"       -> Bencode.fromInt(500)
    )

  private val multipleFileInfo =
    Bencode.fromFields(
      "piece length" -> Bencode.fromInt(20),
      "pieces"       -> Bencode.fromByteVector(ByteVector.fill(20000)(5)),
      "files" -> Bencode.fromValues(
        Bencode.fromFields(
          "length" -> Bencode.fromInt(500),
          "path" -> Bencode.fromValues(
            Bencode.fromString("dir"),
            Bencode.fromString("dir2"),
            Bencode.fromString("dir3")
          )
        ),
        Bencode.fromFields(
          "length" -> Bencode.fromInt(300),
          "path" -> Bencode.fromValues(
            Bencode.fromString("dirA"),
            Bencode.fromString("dirB"),
            Bencode.fromString("dirC")
          )
        ),
        Bencode.fromFields(
          "length" -> Bencode.fromInt(500),
          "path" -> Bencode.fromValues(
            Bencode.fromString("dir"),
            Bencode.fromString("dirr"),
            Bencode.fromString("dirrr")
          )
        )
      ),
      "name" -> Bencode.fromString("filename")
    )

  private def torrentFile(info: Bencode) =
    Bencode.fromFields(
      "info"     -> info,
      "announce" -> Bencode.fromString("blahblah")
    )

  private val torrenFileNames = "debian.torrent" :: "ubuntu.torrent" :: "pop-os.torrent" :: Nil

  def spec: ZSpec[Environment, Failure] =
    suite("Bencode decoders should")(
      test("decode a torrent file from a parsed Bencode structure (single file info)") {
        val expectedTorrentFile =
          TorrentFile(
            info = Info.SingleFile(
              pieceLength = 20,
              pieces = ByteVector.fill(20000)(5),
              name = "filename",
              length = 500
            ),
            announce = "blahblah"
          )

        val decoded = torrentFile(singleFileInfo).cursor.as[TorrentFile]
        assert(decoded)(isRight(equalTo(expectedTorrentFile)))
      },
      test("decode a torrent file from a parsed Bencode structure (multiple file info)") {
        val expectedTorrentFile =
          TorrentFile(
            info = Info.MultipleFile(
              pieceLength = 20,
              pieces = ByteVector.fill(20000)(5),
              name = "filename",
              files = Seq(
                Subfile(
                  length = 500,
                  path = Seq("dir", "dir2", "dir3")
                ),
                Subfile(
                  length = 300,
                  path = Seq("dirA", "dirB", "dirC")
                ),
                Subfile(
                  length = 500,
                  path = Seq("dir", "dirr", "dirrr")
                )
              )
            ),
            announce = "blahblah"
          )

        val decoded = torrentFile(multipleFileInfo).cursor.as[TorrentFile]
        assert(decoded)(isRight(equalTo(expectedTorrentFile)))
      },
      testM("parse and decode a torrent file") {
        for {
          torrentFiles <- ZIO.foreach(torrenFileNames) { filename =>
            ZStream.fromResource(filename).runCollect.map(_.toArray)
          }

          parsed = torrentFiles.map(file => Bencode.parse(ByteVector.view(file)).flatMap(_.cursor.as[TorrentFile]))
        } yield parsed.map(parsed => assert(parsed)(isRight)).reduce(_ && _)
      },
      testM("parse and then stringify and parse again") {
        for {
          torrentFile <- ZStream.fromResource("ubuntu.torrent").runCollect.map(_.toArray).map(ByteVector.apply)
          parsed      <- ZIO.fromEither(Bencode.parse(torrentFile))
          info = parsed.cursor.field("info").as[Info]
          encoded = info.map(_.encode.byteify())
          parsedBack = encoded.flatMap(bytes => Bencode.parse(bytes).flatMap(_.cursor.as[Info]))
        } yield assert(parsedBack)(equalTo(info))
      },
      testM("asd") {
        for {
          torrentFile <- ZStream.fromResource("debian.torrent").runCollect.map(_.toArray).map(ByteVector.apply)
          parsed      <- ZIO.fromEither(Bencode.parse(torrentFile))
          torrent <- ZIO.fromEither(parsed.cursor.as[TorrentFile])
          _ <- Tracker.pingTracker(torrent)
        } yield assertCompletes
      }.provideCustomLayer(Tracker.live)
    )
}
