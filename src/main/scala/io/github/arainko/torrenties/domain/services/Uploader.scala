package io.github.arainko.torrenties.domain.services

import io.github.arainko.torrenties.domain.models.errors._
import io.github.arainko.torrenties.domain.models.network.PeerMessage._
import scodec.bits._
import zio._
import zio.nio.channels.AsynchronousFileChannel
import zio.nio.core.file.Path
import zio.stream.ZStream

import java.nio.file.{Path => JPath, StandardOpenOption}

final case class Uploader(socket: MessageSocket, session: Session, folderPath: JPath) {

  private val filePath = {
    val fileName = session.torrent.info.fold(_.name, _.name)
    Path(folderPath.toString, fileName)
  }

  private val options = StandardOpenOption.READ

  private def commLoop(file: AsynchronousFileChannel) =
    ZStream
      .repeatEffect(socket.readMessage)
      .collectM { case req @ Request(_, _, lenght) =>
        ZIO.ifM(session.isPieceComplete(req.pieceIndex.value))(
          fulfillRequest(file, req)
            .flatMap(socket.writeMessage)
            .zipLeft(session.incrementUploaded(lenght.value)),
          ZIO.fail(PeerMessageError(s"Piece #${req.pieceIndex.value} isn't complete!"))
        )
      }
      .runDrain

  def worker: Task[Unit] =
    for {
      _ <- socket.awaitHandshake(session.torrent)
      _ <- currentBitfield.flatMap(socket.writeMessage)
      _ <- socket.readMessage.repeatUntil(_ == Interested)
      _ <- AsynchronousFileChannel.open(filePath, options).use(commLoop)
    } yield ()

  private def currentBitfield =
    session.currentMeta
      .map(_.bitfield)
      .map(BitVector.bits)
      .map(Bitfield)

  private def fulfillRequest(file: AsynchronousFileChannel, request: Request) = {
    val capacity = request.length.value.toInt
    file.readChunk(capacity, request.begin.value).map { block =>
      Piece(request.pieceIndex, request.begin, ByteVector.apply(block))
    }
  }

}
