
package io.buoyant.test

import com.twitter.io.Buf
import com.twitter.io.Buf.ByteArray
import org.scalacheck.Gen

trait DtabGenerators {
  val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ#._"
  val labelChar = for { i <- Gen.choose(0, alphabet.length) } yield alphabet(i)
  val label: Gen[Buf] = Gen.sized { size =>
    Gen.listOfN(size, labelChar).map(list => ByteArray(list.map(_.toByte):_*))
  }
}
