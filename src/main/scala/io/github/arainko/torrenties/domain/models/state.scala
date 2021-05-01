package io.github.arainko.torrenties.domain.models

import io.github.arainko.torrenties.domain.models.network._
import io.github.arainko.torrenties.domain.models.torrent._
import monocle.macros.Lenses
import scodec.bits.{BitVector, ByteVector}
import zio.Chunk

object state {

  final case class StateBitfield(value: BitVector) extends AnyVal

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

  @Lenses
  final case class PeerState(
    chokeState: ChokeState,
    interestState: InterestState,
    bitfield: StateBitfield
  ) {
    def hasPiece(index: Long): Boolean = bitfield.value.lift(index).getOrElse(false)
  }

  object PeerState {

    def initial(pieceCount: Long): PeerState =
      PeerState(
        chokeState = ChokeState.Choked,
        interestState = InterestState.NotInterested,
        bitfield = StateBitfield(BitVector.fill(pieceCount)(false))
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

  object FullPiece {

    def fromPieces(pieces: Chunk[PeerMessage.Piece]): FullPiece = {
      val bytes = pieces.foldLeft(ByteVector.empty)(_ ++ _.block)
      val hash  = bytes.digest("SHA-1")
      FullPiece(bytes, hash)
    }
  }

  final case class Result(work: Work, fullPiece: FullPiece)

  final case class TorrentMeta(
    torrentFile: TorrentFile,
    bitfield: Chunk[Boolean],
    completedPieces: Int,
    completedBytes: Long
  ) {

    def markCompleted(pieceIndex: Int, pieceBytes: Long): TorrentMeta =
      this.copy(
        bitfield = bitfield.updated(pieceIndex, true),
        completedPieces = completedPieces + 1,
        completedBytes = completedBytes + pieceBytes
      )

    def incompleteWork: Vector[Work] = {
      val work = torrentFile.info.workPieces
      bitfield.zipWithIndex
        .collect {
          case (bit, index) if bit == false => index
        }
        .foldLeft(Vector.empty[Work])(_ :+ work(_))
    }

    def diff(that: TorrentMeta, uploaded: Long): SessionDiff = {
      val downloadedDiff = that.completedBytes - this.completedBytes
      SessionDiff(downloadedDiff, uploaded)
    }

    def isComplete: Boolean    = torrentFile.pieceCount.toInt == completedPieces
    def isNotComplete: Boolean = !isComplete
  }

  object TorrentMeta {

    def empty(torrent: TorrentFile): TorrentMeta =
      TorrentMeta(torrent, Chunk.fill(torrent.pieceCount.toInt)(false), 0, 0)
  }

  final case class SessionDiff(downloadedDiff: Long, uploadedDiff: Long)
}
