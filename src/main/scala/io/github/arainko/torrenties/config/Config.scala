package io.github.arainko.torrenties.config

import zio.config.ConfigDescriptor._

import java.nio.file.Path

final case class Config(downloadFolder: Path)

object Config {
  val descriptor = javaFilePath("downloadFolder")(Config.apply, Config.unapply)


}
