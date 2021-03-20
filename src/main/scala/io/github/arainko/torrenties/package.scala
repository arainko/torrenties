package io.github.arainko

import zio._
import io.github.arainko.torrenties.domain.services._

package object torrenties {
  type Tracker = Has[Tracker.Service]
}
