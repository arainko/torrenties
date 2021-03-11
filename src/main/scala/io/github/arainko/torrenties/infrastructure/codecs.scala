package io.github.arainko.torrenties.infrastructure

import rainko.bencode.derivation.auto._
import rainko.bencode._
import io.github.arainko.torrenties.domain.model._

object codecs {
  private lazy val infoDecoder: Decoder[Info] = bencode =>
    bencode.cursor
      .as[Info.SingleFile]
      .orElse(bencode.cursor.as[Info.MultipleFile])

  implicit lazy val infoDecoderTransformed: Decoder[Info] =
    infoDecoder.withFieldsRenamed { case "piece length" =>
      "pieceLength"
    }
}
