
package com.twitter.finagle.buoyant.test

import com.twitter.finagle.{NameTreeParsers, Path}
import com.twitter.io.Buf
import com.twitter.io.Buf.ByteArray
import org.scalacheck.{Arbitrary, Gen}
import scala.util.Try

trait DtabGenerators {
  val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ._"
  val labelChar = for { i <- Gen.choose(0, alphabet.length) } yield alphabet(i)
  val label: Gen[String] = Gen.sized { size =>
    if (size == 1) Gen.oneOf("#", "*")
    else Gen.listOfN(size, labelChar).map { _.mkString("") }
  } suchThat { str => Try(NameTreeParsers.parsePath(str)).isSuccess }

  implicit def arbPath: Arbitrary[Path] = Arbitrary {
    def pathElems(size: Int) = Gen.listOfN(size, label)
    Gen.sized { size => pathElems(size) map { elems => NameTreeParsers.parsePath(elems.mkString("/"))} }
  }
}
