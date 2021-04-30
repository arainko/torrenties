package io.github.arainko.torrenties.domain.services

import io.github.arainko.torrenties.domain.models.network._
import io.github.arainko.torrenties.domain.models.state._
import io.github.arainko.torrenties.domain.typeclasses._
import io.github.arainko.torrenties.domain.typeclasses.instances._
import monocle.Focus
import monocle.syntax.all._
import zio._

object PeerInfo {

  def make(peers: Seq[PeerAddress], pieceCount: Long): UIO[PeerInfo] = {
    val initialState = peers.map(_ -> PeerState.initial(pieceCount)).toMap
    Ref.make(initialState).map(PeerInfo(pieceCount, _))
  }

}

final case class PeerInfo(pieceCount: Long, state: Ref[Map[PeerAddress, PeerState]]) {

  def peers: UIO[Set[PeerAddress]] = state.get.map(_.keySet)

  def ++ (peers: Seq[PeerAddress]): UIO[Set[PeerAddress]] = 
    for {
      currentPeers <- this.peers
      newlyAdded = peers.toSet.diff(currentPeers)
      withState = newlyAdded.map(_ -> PeerState.initial(pieceCount))
      _ <- state.update(_ ++ withState)
    } yield newlyAdded

  def - (peer: PeerAddress): UIO[Unit] = state.update(_ - peer)
  

  def hasPiece(peer: PeerAddress, index: Long): UIO[Boolean] =
    state.get.map { 
      _.focus()
        .index(peer)
        .getOption
        .exists(_.hasPiece(index))
    }

  def update[A](peer: PeerAddress, value: A)(implicit TU: TypeUpdater[PeerState, A]): UIO[Unit] =
    state.update {
      _.focus()
        .index(peer)
        .andThen(TU.lens)
        .replace(value)
    }

  def updateBitfield(peer: PeerAddress, index: Long, value: Boolean): UIO[Unit] =
    state.update {
      _.focus()
        .index(peer)
        .andThen(PeerState.bitfield)
        .andThen(Focus[StateBitfield](_.value))
        .index(index)
        .replace(value)
    }
}
