package io.github.arainko.torrenties.domain.models

import io.github.arainko.bencode.BencodeError.ParsingFailure
import io.github.arainko.bencode.{BencodeError, DecodingError}

import scala.util.control.NoStackTrace

object errors {
  sealed trait ApplicationError extends NoStackTrace

  sealed trait TrackerError extends ApplicationError

  object TrackerError {
    case object NoHttpAnnounces                        extends TrackerError
    final case class MalformedBencode(message: String) extends TrackerError
    final case class TrackerFailure(message: String)   extends TrackerError
  }

  final case class SerializationError(message: String) extends ApplicationError {
    override def getMessage(): String = message
  }

  object SerializationError {

    def fromBencodeError(err: BencodeError): SerializationError =
      err match {
        case ParsingFailure(message, _) => SerializationError(message)
        case _: DecodingError           => SerializationError("Bencode decoding has failed")
      }
  }

  final case class PeerMessageError(message: String) extends ApplicationError {
    override def getMessage(): String = message
  }

  case object PeerNotFound extends ApplicationError
}
