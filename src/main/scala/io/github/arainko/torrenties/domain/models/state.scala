package io.github.arainko.torrenties.domain.models

import scodec.bits.ByteVector
import scodec.bits.BitVector
import monocle.macros.Lenses

object state {

  sealed trait ChokeState

  object ChokeState {
    case object Choked   extends ChokeState
    case object Unchoked extends ChokeState
  }

  sealed trait InterestState

  object InterestState {
    case object Interested    extends InterestState
    case object NotInterested extends InterestState
  }

  @Lenses final case class PeerState(
    chokeState: ChokeState,
    interestState: InterestState,
    peerChokeState: ChokeState,
    peerInterestState: InterestState,
    peerBitfield: BitVector
  ) {
    def hasPiece(index: Long): Boolean = peerBitfield.lift(index).getOrElse(false)
  }

  object PeerState {

    def initial(pieceCount: Long): PeerState =
      PeerState(
        ChokeState.Choked,
        InterestState.NotInterested,
        ChokeState.Choked,
        InterestState.NotInterested,
        BitVector.fill(pieceCount)(false)
      )
  }

  final case class Work(index: Long, hash: ByteVector, length: Long)
}
