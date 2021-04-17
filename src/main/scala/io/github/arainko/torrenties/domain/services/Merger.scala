package io.github.arainko.torrenties.domain.services

import zio.nio.file.Files
import zio.nio.core.file.Path
import zio.Queue
import zio.stream._
import io.github.arainko.torrenties.domain.models.state._
import io.github.arainko.torrenties.domain.models.torrent._
import java.nio.file.StandardOpenOption
import zio.nio.channels.AsynchronousFileChannel
import java.nio.file.attribute.FileAttribute
import zio.ZIO
import zio.blocking.Blocking
import java.nio.file.Paths
import java.nio.file.OpenOption

object Merger {

  private val opt: Set[OpenOption] = Set(StandardOpenOption.CREATE, StandardOpenOption.WRITE)

  def daemon(torrent: TorrentFile, queue: Queue[Result]) =
    ZStream
      .fromQueue(queue)
      .mapM { res =>
        val pieceLength = torrent.info.fold(_.pieceLength, _.pieceLength)
        val position = pieceLength * res.work.index
        val name        = torrent.info.fold(_.name, _.name)
        ZStream
          .fromChunk(res.fullPiece.chunk)
          .run(ZSink.fromFile(Paths.get(name), position, opt))
      }
}
