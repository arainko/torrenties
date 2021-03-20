package io.github.arainko.torrenties.domain

import io.github.arainko.torrenties.domain.models.errors._
import scodec.Codec
import zio._
import scodec.bits.ByteVector
import scodec.bits.BitVector
import scodec.DecodeResult

object syntax {

  implicit class ScodecOps[A](private val codec: Codec[A]) extends AnyVal {

    def encodeM(value: A): IO[SerializationError, ByteVector] =
      ZIO
        .fromEither(codec.encode(value).toEither)
        .mapError(e => SerializationError(e.message))
        .map(_.toByteVector)

    def encodeChunkM(value: A) = encodeM(value).map(v => Chunk.fromArray(v.toArray))

    def decodeM(value: BitVector): IO[SerializationError, DecodeResult[A]] =
      ZIO
        .fromEither(codec.decode(value).toEither)
        .mapError(e => SerializationError(e.message))

    def decodeChunkM(value: Chunk[Byte]): IO[SerializationError, DecodeResult[A]] =
      decodeM(ByteVector.view(value.toArray).bits)

    def decodeSingleM(value: BitVector): IO[SerializationError, A] =
      ZIO
        .fromEither(codec.decodeValue(value).toEither)
        .mapError(e => SerializationError(e.message))

    def decodeSingleChunkM(value: Chunk[Byte]) = decodeSingleM(ByteVector.view(value.toArray).bits)
  }
}
