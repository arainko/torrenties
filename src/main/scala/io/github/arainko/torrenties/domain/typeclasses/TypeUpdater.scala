package io.github.arainko.torrenties.domain.typeclasses

import io.github.arainko.torrenties.domain.models.state._
import monocle._

trait TypeUpdater[A, B] {
  def lens: Lens[A, B]
}

object TypeUpdater {
  def apply[A, B](implicit updater: TypeUpdater[A, B]): TypeUpdater[A, B] = updater

  def fromLens[A, B](optic: Lens[A, B]) =
    new TypeUpdater[A, B] {
      def lens: Lens[A, B] = optic
    }

  implicit val peerStateChokeState: TypeUpdater[PeerState, ChokeState] =
    fromLens(PeerState.chokeState)

  implicit val peerStateInterestState: TypeUpdater[PeerState, InterestState] =
    fromLens(PeerState.interestState)

  implicit val peerStateBitfield: TypeUpdater[PeerState, StateBitfield] =
    fromLens(PeerState.bitfield)

}
