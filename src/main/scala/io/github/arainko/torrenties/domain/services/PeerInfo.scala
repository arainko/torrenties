package io.github.arainko.torrenties.domain.services

import io.github.arainko.torrenties.domain.models.network._
import io.github.arainko.torrenties.domain.models.state._
import monocle.function.Index
import monocle.syntax.all._
import monocle.{Lens, Optional}
import scodec.bits.BitVector
import zio._

object PeerInfo {

  def make(peers: Seq[PeerAddress], pieceCount: Long): UIO[PeerInfo] = {
    val initialState = peers.map(_ -> PeerState.initial(pieceCount)).toMap
    Ref.make(initialState).map(PeerInfo.apply)
  }

  // TODO: Move somewhere else?
  implicit val indexBitVector: Index[BitVector,Long,Boolean] = new Index[BitVector, Long, Boolean] {

    override def index(i: Long): Optional[BitVector, Boolean] =
      Optional[BitVector, Boolean](_.lift(i))(bool => vec => vec.update(i, bool))
  }

}

final case class PeerInfo(state: Ref[Map[PeerAddress, PeerState]]) extends AnyVal {

  import PeerInfo._

  def hasPiece(peer: PeerAddress, index: Long): UIO[Boolean] =
    state.get.map { s =>
      s.focus()
        .index(peer)
        .getOption
        .exists(_.hasPiece(index))
    }

  def updatePeerInterest(peer: PeerAddress, interest: InterestState): UIO[Unit] =
    updateProperty(peer)(PeerState.peerInterestState)(interest)

  def updateInterest(peer: PeerAddress, interest: InterestState): UIO[Unit] =
    updateProperty(peer)(PeerState.interestState)(interest)

  def updatePeerChoke(peer: PeerAddress, choke: ChokeState): UIO[Unit] =
    updateProperty(peer)(PeerState.peerChokeState)(choke)

  def updateChoke(peer: PeerAddress, choke: ChokeState): UIO[Unit] = updateProperty(peer)(PeerState.chokeState)(choke)

  def updateBitfield(peer: PeerAddress, index: Long, value: Boolean): IO[Nothing,Unit] =
    state.update {
      _.focus()
        .index(peer)
        .andThen(PeerState.peerBitfield)
        .index(index)
        .replace(value)
    }

  def setBitfield(peer: PeerAddress, bitfield: BitVector): UIO[Unit] =
    updateProperty(peer)(PeerState.peerBitfield)(bitfield)

  private def updateProperty[A](peer: PeerAddress)(lens: Lens[PeerState, A])(property: A) =
    state.update {
      _.focus()
        .index(peer)
        .andThen(lens)
        .replace(property)
    }
}
