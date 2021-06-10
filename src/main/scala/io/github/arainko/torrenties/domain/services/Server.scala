package io.github.arainko.torrenties.domain.services

import io.github.arainko.torrenties.config.FolderConfig
import zio._
import zio.logging.Logging
import zio.nio.channels._
import zio.nio.core.SocketAddress
import zio.stream.ZStream

object Server {

  def start(port: Int, session: Session): ZManaged[Has[FolderConfig] with Logging, Exception, Unit] =
    AsynchronousServerSocketChannel().mapM { socket =>
      for {
        folderConfig <- ZIO.service[FolderConfig]
        address      <- SocketAddress.inetSocketAddress("0.0.0.0", port)
        _            <- socket.bind(address)
        connections <- ZStream
          .repeatEffect(socket.accept.preallocate)
          .flatMap(ZStream.managed(_))
          .mapM(MessageSocket.fromConnectedSocket)
          .mapM(socket => Uploader(socket, session, folderConfig.downloadFolder).worker.fork)
          .runDrain
      } yield connections
    }

}
