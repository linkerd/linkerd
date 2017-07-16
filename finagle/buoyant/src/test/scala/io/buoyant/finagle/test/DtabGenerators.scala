
package io.buoyant.finagle.test

import com.twitter.finagle.Path
import com.twitter.io.Buf
import com.twitter.io.Buf.ByteArray
import org.scalacheck.{Arbitrary, Gen}

trait DtabGenerators {
  val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ._"
  val labelChar = for { i <- Gen.choose(0, alphabet.length) } yield alphabet(i)
  val label: Gen[Buf] = Gen.sized { size =>
    if (size == 1) Gen.oneOf("#", "*")
    else Gen.listOfN(size, labelChar).map { _.mkString("") }
  }.map { str =>
    ByteArray(str.map(_.toByte):_*)
  }

  implicit def arbPath: Arbitrary[Path] = Arbitrary {
    def pathElems(size: Int) = Gen.listOfN(size, label)
    Gen.sized { size => pathElems(size).map{ bufs => Path(bufs:_*) } }
  }
}
