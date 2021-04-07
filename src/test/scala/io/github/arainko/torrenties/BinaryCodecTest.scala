package io.github.arainko.torrenties

import zio.test._
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
        val encoded = Binary.peerMessageEnc.encode(request)
        val costam = (uint32 ~ uint8 ~ uint32 ~ uint32 ~ uint32)
          .encode(13L ~ 6 ~ 0L ~ 0L ~ 0L)
        // val expected = hex"0xD"
        println(encoded)
        println(costam)
        assertCompletes
      }
    )
}
