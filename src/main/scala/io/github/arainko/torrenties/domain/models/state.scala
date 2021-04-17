package io.github.arainko.torrenties.domain.models

import io.github.arainko.torrenties.domain.models.network._
import monocle.macros.Lenses
import scodec.bits.{BitVector, ByteVector}
import zio.Chunk

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

  final case class Work(index: Long, hash: ByteVector, length: Long) {

    lazy val requests: List[PeerMessage.Request] = {
      val blockSize = Math.pow(2, 14).toLong
      List.unfold(length) { lengthLeft =>
        val currentBlockSize = if (lengthLeft <= blockSize) lengthLeft else blockSize
        val offset           = length - lengthLeft
        val request          = PeerMessage.Request(UInt32(index), UInt32(offset), UInt32(currentBlockSize))
        Option.when(lengthLeft > 0)(request -> (lengthLeft - currentBlockSize))
      }
    }
  }

  final case class FullPiece(bytes: ByteVector, hash: ByteVector) {
    lazy val chunk: Chunk[Byte] = Chunk.fromArray(bytes.toArray)
  }

  final case class Result(work: Work, fullPiece: FullPiece)

  object FullPiece {
    def fromPieces(pieces: Chunk[PeerMessage.Piece]): FullPiece = {
      val bytes = pieces.foldLeft(ByteVector.empty)(_ ++ _.block)
      val hash = bytes.digest("SHA-1")
      FullPiece(bytes, hash)
    }
  }
}
