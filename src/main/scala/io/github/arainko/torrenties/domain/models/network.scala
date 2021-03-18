package io.github.arainko.torrenties.domain.models

object network {
  final case class Segment(value: Int) extends AnyVal
  final case class Port(value: Int)    extends AnyVal
  final case class IP(seg1: Segment, seg2: Segment, seg3: Segment, seg4: Segment, port: Port) {
    def show: String = s"${seg1.value}.${seg2.value}.${seg3.value}.${seg4.value}:${port.value}"
  }
}
