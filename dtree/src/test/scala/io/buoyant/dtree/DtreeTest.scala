package io.buoyant.dtree

import com.twitter.finagle.{Dtab, Name, NameTree, Path}
import com.twitter.io.Buf
import org.scalatest.FunSuite

class DtreeTest extends FunSuite {

  val root = new MapDtree {
    // There's no global policy. All policy is stored in dtrees.
    val dtab = Dtab.empty

    val tree = Map(
      "p" -> new MapDtree {
        val tree = Map.empty[String, Dtree]
        val dtab = Dtab.read("""
          /.p/us-east1 => /#/io.l5d.namer.us-east-1 ;
          /p => /z/us-east-1 ;
        """)
      },

      "s" -> new MapDtree {
        val dtab = Dtab.read("""
          /s => /p/DEFAULT ;
        """)

        val tree = Map(
          "SandwichMaker" -> new MapDtree {
            val tree = Map.empty[String, Dtree]
            val dtab = Dtab.read("""
              /.p => /p/CAFE ;
              /.s => /.p/DarkProxy | /.p ;
              /s/SandwichMaker => 9 * /.s/SandwichMaker-abcdef0 & /.s/SandwichMaker-fedcba1 ;
            """)
          },
          "Farmer" -> new MapDtree {
            val tree = Map.empty[String, Dtree]
            val dtab = Dtab.empty
          }
        )
      },

      "c" -> new MapDtree {
        val dtab = Dtab.read("""
          # The only way out of /c is through /s.
          /$ => ! ;
          /# => ! ;
          /p => ! ;
          /c/* => /s ;
        """)

        val tree = Map(
          "Caterer" -> new MapDtree {

            // If a name doesn't exist under /c/Caterer, it will fail
            // to resolve:
            val dtab = Dtab.read("""
              # Caterer can only talk to specifically configured clients:
              /c/Caterer/* => !;
            """)

            val tree = Map(
              "SandwichMaker" -> new MapDtree {
                val tree = Map.empty[String, Dtree]
                val dtab = Dtab.read("""
                  /debug => /s/Debugger/SandwichMaker ;
                  /method => /s/SandwichMaker ;
                  /method/BuggyMethod => /debug/BuggyMethod ;
                  /c/Caterer/SandwichMaker => /method ;
                """)
              }
            )
          }
        )
      }
    )
  }

  def delegate(path: String): NameTree[Name.Path] =
    Dtree.delegate(root, Path.read(path))

  test("dtrees delegate over trees of dtabs") {
    assert(delegate("/c/Caterer/SandwichMaker/AnyMethod") ==
      NameTree.Neg)

    assert(delegate("/c/Caterer/SandwichMaker/BuggyMethod") ==
      NameTree.Neg)
  }

  test("prefixes can blackhole routes") {
    assert(delegate("/c/Caterer/TacoTruck/Burrito") == NameTree.Fail)
  }

  test("prefixes can provide sane defaults") {
    assert(delegate("/c/SandwichMaker/Farmer/Lettuce") ==
      NameTree.Neg)
  }
}
