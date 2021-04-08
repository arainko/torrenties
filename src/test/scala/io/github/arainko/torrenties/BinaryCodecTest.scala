package io.github.arainko.torrenties

import zio.test._
import zio.test.Assertion._
import io.github.arainko.torrenties.domain.models.network.PeerMessage._
import io.github.arainko.torrenties.domain.models.network._
import io.github.arainko.torrenties.domain.codecs.Binary
import scodec.bits._
import scodec.codecs._

object BinaryCodecTest extends DefaultRunnableSpec {

  def spec: ZSpec[Environment, Failure] =
    suite("Binary codecs should")(
      test("Poperly encode Request") {
        val request = Request(UInt32(0), UInt32(0), UInt32(0))
        val encoded = Binary.peerMessageEnc.encode(request).toEither
        val expected = (uint32 ~ uint8 ~ uint32 ~ uint32 ~ uint32)
          .encode(13L ~ 6 ~ 0L ~ 0L ~ 0L)
          .toEither 
        assert(encoded)(equalTo(expected))
      },
      test("Properly decode Piece") {
        val pieceCodec = uint8 ~ uint32 ~ uint32 ~ bytes
        val encoded = pieceCodec.encode(7 ~ 0L ~ 0L ~ ByteVector.fill(400L)(1))
        val expected = Piece(UInt32(0), UInt32(0), ByteVector.fill(400L)(1))
        val decoded = encoded.flatMap(Binary.peerMessageDec(409).decode)
          .toEither
          .map(_.value)
        assert(decoded)(isRight(equalTo(expected)))
      }
    )
}
