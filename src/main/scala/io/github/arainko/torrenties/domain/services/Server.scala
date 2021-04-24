package io.github.arainko.torrenties.domain.services

import zio.stream.ZStream

import zio.nio.channels._
import zio.nio.core.SocketAddress

object Server {

  def start(port: Int) = ZStream.unwrapManaged {
    AsynchronousServerSocketChannel().mapM { socket =>
      for {
        address <- SocketAddress.inetSocketAddress("0.0.0.0", port)
        _       <- socket.bind(address)
        connections = ZStream
          .repeatEffect(socket.accept.preallocate)
          .flatMap(ZStream.managed(_))
      } yield connections
    }
  }
}
