package io.github.arainko.torrenties.domain.services

import zio.blocking.Blocking
import zio.stream._

object Server {

  def start(port: Int): ZStream[Blocking,Throwable,ZStream.Connection] =
    ZStream.fromSocketServer(port, Some("0.0.0.0"))
}
