package io.github.arainko.torrenties.domain.services

import io.github.arainko.torrenties.domain.models.state._
import io.github.arainko.torrenties.domain.models.torrent._

import zio._

object Session {
  def fromMeta(meta: TorrentMeta): UIO[Session] = Ref.make(meta).map(Session(meta, _))
}

final case class Session(initialMeta: TorrentMeta, currentMetaRef: Ref[TorrentMeta]) {

  def currentMeta: UIO[TorrentMeta] = currentMetaRef.get

  def diff: UIO[SessionDiff] = currentMetaRef.get.map(_.diff(initialMeta))

  def torrent: TorrentFile = initialMeta.torrentFile

  def markCompleted(pieceIndex: Int, pieceBytes: Long): UIO[Unit] =
    currentMetaRef.update(_.markCompleted(pieceIndex, pieceBytes))

  def isComplete: UIO[Boolean] = currentMetaRef.get.map(_.isComplete)

}
