package io.github.arainko.torrenties.config

import zio.config.ConfigDescriptor._

import java.nio.file.Path
import zio.config.ConfigDescriptor

final case class FolderConfig(downloadFolder: Path)

object FolderConfig {
  val descriptor: ConfigDescriptor[FolderConfig] = javaFilePath("downloadFolder")(FolderConfig.apply, FolderConfig.unapply)
}
