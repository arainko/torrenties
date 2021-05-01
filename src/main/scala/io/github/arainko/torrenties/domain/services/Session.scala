package io.github.arainko.torrenties.domain.services

import io.github.arainko.torrenties.domain.models.state._
import io.github.arainko.torrenties.domain.models.torrent._

import zio._

object Session {

  def fromMeta(meta: TorrentMeta): UIO[Session] =
    for {
      metaRef     <- Ref.make(meta)
      uploadedRef <- Ref.make(0L)
    } yield Session(meta, metaRef, uploadedRef)
}

final case class Session(initialMeta: TorrentMeta, currentMetaRef: Ref[TorrentMeta], uploaded: Ref[Long]) {

  def currentMeta: UIO[TorrentMeta] = currentMetaRef.get

  def diff: UIO[SessionDiff] =
    for {
      currentUploaded <- uploaded.get
      meta            <- currentMeta
    } yield meta.diff(initialMeta, currentUploaded)

  def torrent: TorrentFile = initialMeta.torrentFile

  def markCompleted(pieceIndex: Int, pieceBytes: Long): UIO[Unit] =
    currentMetaRef.update(_.markCompleted(pieceIndex, pieceBytes))

  def isPieceComplete(pieceIndex: Long): UIO[Boolean] =
    currentMeta.map(_.bitfield.applyOrElse(pieceIndex.toInt, (_: Int) => false))

  def incrementUploaded(value: Long): UIO[Unit] = uploaded.update(_ + value)

  def isComplete: UIO[Boolean] = currentMetaRef.get.map(_.isComplete)

}
