package io.github.arainko.torrenties.domain.typeclasses

import monocle.Optional
import monocle.function.Index
import scodec.bits.BitVector

object instances {
  implicit val indexBitVector: Index[BitVector, Long, Boolean] = new Index[BitVector, Long, Boolean] {

    override def index(i: Long): Optional[BitVector, Boolean] =
      Optional[BitVector, Boolean](_.lift(i))(bool => vec => vec.update(i, bool))
  }
}
