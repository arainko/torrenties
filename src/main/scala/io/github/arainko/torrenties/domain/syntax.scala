package io.github.arainko.torrenties.domain

import io.github.arainko.torrenties.domain.models.errors._
import scodec._
import scodec.bits.{BitVector, ByteVector}
import zio._

object syntax {

  implicit class ScodecCodecOps[A](private val codec: Codec[A]) extends AnyVal {

    def encodeM(value: A): IO[SerializationError, ByteVector] = codec.asEncoder.encodeM(value)

    def encodeChunkM(value: A): ZIO[Any, SerializationError, Chunk[Byte]] = codec.asEncoder.encodeChunkM(value)

    def decodeM(value: BitVector): IO[SerializationError, DecodeResult[A]] = codec.asDecoder.decodeM(value)

    def decodeChunkM(value: Chunk[Byte]): IO[SerializationError, DecodeResult[A]] = codec.asDecoder.decodeChunkM(value)

    def decodeSingleM(value: BitVector): IO[SerializationError, A] = codec.asDecoder.decodeSingleM(value)

    def decodeSingleChunkM(value: Chunk[Byte]): IO[SerializationError, A] = codec.asDecoder.decodeSingleChunkM(value)
  }

  implicit class ScodecEncoderOps[A](private val encoder: Encoder[A]) extends AnyVal {

    def encodeM(value: A): IO[SerializationError, ByteVector] =
      ZIO
        .fromEither(encoder.encode(value).toEither)
        .mapError(e => SerializationError(e.message))
        .map(_.toByteVector)

    def encodeChunkM(value: A): ZIO[Any, SerializationError, Chunk[Byte]] =
      encodeM(value).map(v => Chunk.fromArray(v.toArray))
  }

  implicit class ScodecDecoderOps[A](private val decoder: Decoder[A]) extends AnyVal {

    def decodeM(value: BitVector): IO[SerializationError, DecodeResult[A]] =
      ZIO
        .fromEither(decoder.decode(value).toEither)
        .mapError(e => SerializationError(e.message))

    def decodeChunkM(value: Chunk[Byte]): IO[SerializationError, DecodeResult[A]] =
      decodeM(ByteVector.view(value.toArray).bits)

    def decodeSingleM(value: BitVector): IO[SerializationError, A] =
      ZIO
        .fromEither(decoder.decodeValue(value).toEither)
        .mapError(e => SerializationError(e.message))

    def decodeSingleChunkM(value: Chunk[Byte]): IO[SerializationError, A] =
      decodeSingleM(ByteVector.view(value.toArray).bits)
  }
}
