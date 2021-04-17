package io.github.arainko.torrenties.domain.services

import io.github.arainko.torrenties.domain.models.state._
import io.github.arainko.torrenties.domain.models.torrent._
import zio.Queue
import zio.blocking.Blocking
import zio.stream._

import java.nio.file.{OpenOption, Paths, StandardOpenOption}

object Merger {

  private val opt: Set[OpenOption] = Set(StandardOpenOption.CREATE, StandardOpenOption.WRITE)

  def daemon(torrent: TorrentFile, queue: Queue[Result]): ZStream[Blocking, Throwable, Long] =
    ZStream
      .fromQueue(queue)
      .mapM { res =>
        val pieceLength = torrent.info.fold(_.pieceLength, _.pieceLength)
        val position    = pieceLength * res.work.index
        val name        = torrent.info.fold(_.name, _.name)
        ZStream
          .fromChunk(res.fullPiece.chunk)
          .run(ZSink.fromFile(Paths.get(name), position, opt))
      }
}
