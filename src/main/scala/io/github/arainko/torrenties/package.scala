package io.github.arainko

import io.github.arainko.torrenties.domain.services._
import zio._

package object torrenties {
  type Tracker = Has[Tracker.Service]
}
