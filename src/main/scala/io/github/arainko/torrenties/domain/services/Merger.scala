package io.github.arainko.torrenties.domain.services

import io.github.arainko.torrenties._
import io.github.arainko.torrenties.config.FolderConfig
import io.github.arainko.torrenties.domain.models.state._
import io.github.arainko.torrenties.domain.models.torrent._
import zio.blocking.Blocking
import zio.config._
import zio.macros.accessible
import zio.stream._
import zio.{Queue, _}

import java.nio.file.{OpenOption, Paths, StandardOpenOption}
import scodec.bits.BitVector
import scodec.bits.ByteVector
import zio.logging.log
import zio.logging.Logging

@accessible
object Merger {

  trait Service {
    def meta(torrent: TorrentFile): Task[Ref[TorrentMeta]]
    def daemon(torrent: TorrentFile, queue: Queue[Result]): Stream[Throwable, Unit]
  }

  val live: URLayer[Has[FolderConfig] & Blocking with Logging, Merger] =
    ZLayer.fromFunction { env =>
      new Service {
        private val config                   = env.get[FolderConfig]
        private val options: Set[OpenOption] = Set(StandardOpenOption.CREATE, StandardOpenOption.WRITE)

        private def filePath(torrent: TorrentFile) = {
          val fileName = torrent.info.fold(_.name, _.name)
          Paths.get(config.downloadFolder.toString, fileName)
        }

        private def fileSink(torrent: TorrentFile, res: Result) = {
          val fullPath    = filePath(torrent)
          val pieceLength = torrent.info.fold(_.pieceLength, _.pieceLength)
          val position    = pieceLength * res.work.index
          ZSink.fromFile(fullPath, position, options)
        }

        def meta(torrent: TorrentFile): UIO[Ref[TorrentMeta]] = {
          val path       = filePath(torrent)
          val pieceSize  = torrent.info.fold(_.pieceLength, _.pieceLength)
          val pieceCount = torrent.info.hashPieces.size
          for {
            metaRef    <- Ref.make[TorrentMeta](TorrentMeta.empty(pieceCount))
            pieceIndex <- Ref.make[Int](0)
            result <- ZStream
              .fromFile(path, pieceSize.toInt)
              .foreachChunk { piece =>
                for {
                  index <- pieceIndex.get
                  hash      = torrent.info.hashPieces(index)
                  pieceHash = ByteVector(piece).digest("SHA-1")
                  _ <- log.debug(s"Checking piece ${index} -> ${hash == pieceHash}")
                  _ <- metaRef.update(meta => if (hash == pieceHash) meta.markCompleted(index) else meta)
                  _ <- pieceIndex.update(_ + 1)
                } yield ()
              }
              .fold(_ => metaRef, _ => metaRef)
          } yield result
        }
          .provide(env)

        def daemon(torrent: TorrentFile, queue: Queue[Result]): Stream[Throwable, Unit] =
          ZStream
            .fromQueue(queue)
            .mapM { res =>
              ZStream
                .fromChunk(res.fullPiece.chunk)
                .run(fileSink(torrent, res))
                .unit
            }
            .provide(env)
      }
    }

}
