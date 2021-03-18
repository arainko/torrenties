package io.github.arainko.torrenties.domain.codecs

import rainko.bencode._
import rainko.bencode.syntax._
import rainko.bencode.derivation.semiauto._
import io.github.arainko.torrenties.domain.models.torrent._

object bencode {
  implicit val subfileDecoder: Decoder[Subfile] = deriveDecoder
  implicit val subfileEncoder: Encoder[Subfile] = deriveEncoder

  implicit val singleFileInfoDecoder: Decoder[Info.SingleFile] = deriveDecoder
  implicit val singleFileInfoEncoder: Encoder[Info.SingleFile] = deriveEncoder

  implicit val multipleFileInfoDecoder: Decoder[Info.MultipleFile] = deriveDecoder
  implicit val multipleFileInfoEncoder: Encoder[Info.MultipleFile] = deriveEncoder

  implicit val announceResponseDecoder: Decoder[AnnounceResponse] =
    deriveDecoder[AnnounceResponseRaw].map(_.toDomain)

  implicit val infoDecoder: Decoder[Info] =
    List[Decoder[Info]](
      Decoder[Info.SingleFile].widen,
      Decoder[Info.MultipleFile].widen
    )
      .reduce(_ or _)
      .withFieldsRenamed { case "piece length" => "pieceLength" }

  private val infoEncoder: Encoder[Info] = {
    case i: Info.SingleFile   => i.encode
    case i: Info.MultipleFile => i.encode
  }

  implicit val infoEncoderTransformed: Encoder[Info] =
    infoEncoder
      .withFieldsRenamed { case "pieceLength" => "piece length" }

  implicit val torrentFileDecoder: Decoder[TorrentFile] =
    deriveDecoder[TorrentFile]
      .withFieldsRenamed {
        case "announce-list" => "announceList"
        case "creation date" => "creationDate"
        case "created by"    => "createdBy"
      }

}
