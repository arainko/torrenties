import rainko.bencode.syntax._
import rainko.bencode.derivation.auto._

object Main extends App {
  val cos = Option(5).encode
  println(cos)
}