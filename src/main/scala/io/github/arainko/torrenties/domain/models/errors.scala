package io.github.arainko.torrenties.domain.models

import scala.util.control.NoStackTrace

object errors {
  sealed trait ApplicationError extends NoStackTrace

  sealed trait TrackerError extends ApplicationError

  object TrackerError {
    case object NoHttpAnnounces extends TrackerError
    final case class MalformedBencode(message: String) extends TrackerError
    final case class TrackerFailure(message: String) extends TrackerError
  }

  final case class SerializationError(message: String) extends ApplicationError {
    override def getMessage(): String = message
  }

  final case class PeerMessageError(message: String) extends ApplicationError  {
    override def getMessage(): String = message
  }

  case object PeerNotFound extends ApplicationError
}
