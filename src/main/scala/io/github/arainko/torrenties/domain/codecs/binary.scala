package io.github.arainko.torrenties.domain.codecs

import io.github.arainko.torrenties.domain.models.network._
import scodec._
import scodec.codecs._

object binary {
  val segment: Codec[Segment] = uint8.as[Segment]

  val port: Codec[Port] = uint16.as[Port]

  val ip: Codec[IP] = (segment :: segment :: segment :: segment :: port).as[IP]
}
