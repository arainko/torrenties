package io.github.arainko.torrenties.domain.services

import io.github.arainko.torrenties._
import io.github.arainko.torrenties.config.FolderConfig
import io.github.arainko.torrenties.domain.models.state._
import io.github.arainko.torrenties.domain.models.torrent._
import scodec.bits.ByteVector
import zio.blocking.Blocking
import zio.macros.accessible
import zio.stream._
import zio._
import zio.console._

import java.nio.file.{OpenOption, Paths, StandardOpenOption}

@accessible
object Merger {

  trait Service {
    def meta(torrent: TorrentFile): UIO[TorrentMeta]
    def daemon(torrent: TorrentFile, queue: Queue[Result]): Stream[Throwable, PieceIndex]
  }

  val live: URLayer[Has[FolderConfig] with Blocking with Console, Merger] =
    ZLayer.fromFunction { env =>
      new Service {
        private val config                   = env.get[FolderConfig]
        private val options: Set[OpenOption] = Set(StandardOpenOption.CREATE, StandardOpenOption.WRITE)

        private def filePath(torrent: TorrentFile) = {
          val fileName = torrent.info.fold(_.name, _.name)
          Paths.get(config.downloadFolder.toString, fileName)
        }

        private def fileSink(torrent: TorrentFile, res: Result) = {
          val fullPath = filePath(torrent)
          val position = torrent.pieceLength * res.work.index
          ZSink.fromFile(fullPath, position, options)
        }

        def meta(torrent: TorrentFile): UIO[TorrentMeta] = {
          val path       = filePath(torrent)
          val pieceSize  = torrent.pieceLength
          val pieceCount = torrent.pieceCount
          val emptyMeta  = TorrentMeta.empty(torrent)
          ZStream
            .fromFile(path, pieceSize.toInt)
            .mapChunks(chunk => Chunk(chunk))
            .zipWithIndex
            .tap { case (_, index) => putStrLn(s"Verifying integrity of piece #${index}") }
            .fold(emptyMeta) { (meta, pieceAndIndex) =>
              val (piece, index) = pieceAndIndex
              val hash           = torrent.info.hashPieces(index.toInt)
              val pieceHash      = ByteVector(piece).digest("SHA-1")
              if (hash == pieceHash) meta.markCompleted(index.toInt) else meta
            }
            .fold(_ => emptyMeta, identity)
            .provide(env)
        }

        def daemon(torrent: TorrentFile, queue: Queue[Result]): Stream[Throwable, PieceIndex] =
          ZStream
            .fromQueue(queue)
            .mapM { res =>
              val pieceIndex = res.work.index.toInt
              ZStream
                .fromChunk(res.fullPiece.chunk)
                .run(fileSink(torrent, res))
                .as(PieceIndex(res.work.index))
            }
            .provide(env)
      }
    }

}
