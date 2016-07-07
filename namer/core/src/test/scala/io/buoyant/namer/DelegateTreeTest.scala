package io.buoyant.namer

import com.twitter.finagle.{Dentry, Path}
import io.buoyant.namer.DelegateTree._
import org.scalatest.FunSuite

class DelegateTreeTest extends FunSuite {

  test("simplify collapses nodes with the same path") {

    val orig =
      Delegate(Path.read("/a"), Dentry.nop,
        Delegate(Path.read("/b"), Dentry.read("/a => /b"),
          Alt(Path.read("/b"), Dentry.read("/b => /c|/d"),
            Leaf(Path.read("/c"), Dentry.read("/b => /c|/d"), Path.read("/c")),
            Leaf(Path.read("/d"), Dentry.read("/b => /c|/d"), Path.read("/d"))
          )
        )
      )

    val simplified =
      Delegate(Path.read("/a"), Dentry.nop,
        Alt(Path.read("/b"), Dentry.read("/a => /b"),
          Leaf(Path.read("/c"), Dentry.read("/b => /c|/d"), Path.read("/c")),
          Leaf(Path.read("/d"), Dentry.read("/b => /c|/d"), Path.read("/d"))
        )
      )

    assert(orig.simplified == simplified)
  }

  test("simplify converts single branch alts into delegates") {
    val orig =
      Delegate(Path.read("/a"), Dentry.nop,
        Delegate(Path.read("/b"), Dentry.read("/a => /b"),
          Alt(Path.read("/b"), Dentry.read("/b => /c"),
            Leaf(Path.read("/c"), Dentry.read("/b => /c"), Path.read("/c"))
          )
        )
      )

    val simplified =
      Delegate(Path.read("/a"), Dentry.nop,
        Delegate(Path.read("/b"), Dentry.read("/a => /b"),
          Leaf(Path.read("/c"), Dentry.read("/b => /c"), Path.read("/c"))
        )
      )

    assert(orig.simplified == simplified)
  }

  test("simplify converts no branch alts into neg") {
    val orig =
      Delegate(Path.read("/a"), Dentry.nop,
        Delegate(Path.read("/b"), Dentry.read("/a => /b"),
          Alt(Path.read("/b"), Dentry.read("/b => ~"))
        )
      )

    val simplified =
      Delegate(Path.read("/a"), Dentry.nop,
        Neg(Path.read("/b"), Dentry.read("/a => /b"))
      )

    assert(orig.simplified == simplified)
  }

  test("simplify converts single branch unions into delegates") {
    val orig =
      Delegate(Path.read("/a"), Dentry.nop,
        Delegate(Path.read("/b"), Dentry.read("/a => /b"),
          Union(Path.read("/b"), Dentry.read("/b => /c"),
            Weighted(1.0, Leaf(Path.read("/c"), Dentry.read("/b => /c"), Path.read("/c")))
          )
        )
      )

    val simplified =
      Delegate(Path.read("/a"), Dentry.nop,
        Delegate(Path.read("/b"), Dentry.read("/a => /b"),
          Leaf(Path.read("/c"), Dentry.read("/b => /c"), Path.read("/c"))
        )
      )

    assert(orig.simplified == simplified)
  }

  test("simplify converts no branch unions into neg") {
    val orig =
      Delegate(Path.read("/a"), Dentry.nop,
        Delegate(Path.read("/b"), Dentry.read("/a => /b"),
          Union(Path.read("/b"), Dentry.read("/b => ~"))
        )
      )

    val simplified =
      Delegate(Path.read("/a"), Dentry.nop,
        Neg(Path.read("/b"), Dentry.read("/a => /b"))
      )

    assert(orig.simplified == simplified)
  }

}
