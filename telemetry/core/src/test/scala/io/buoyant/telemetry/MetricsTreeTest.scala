package io.buoyant.telemetry

import com.twitter.finagle.stats.Verbosity
import io.buoyant.test.FunSuite
import org.scalatest.Matchers

class MetricsTreeTest extends FunSuite with Matchers {

  test("flattening an empty MetricsTree is empty") {
    val emptyTree = MetricsTree()
    MetricsTree.flatten(emptyTree) shouldBe 'empty
  }

  test("flattening an empty MetricsTree with a prefix is empty") {
    val emptyTree = MetricsTree()
    MetricsTree.flatten(emptyTree, "my great prefix") shouldBe 'empty
  }

  test("flattening a MetricsTree with a single item") {
    val tree = MetricsTree()
    val scope = tree.resolve(Seq("my", "great", "scope"))
    val stat = scope.mkStat(Verbosity.Default)
    MetricsTree.flatten(tree) should contain only (
      "my/great/scope" -> stat
    )
  }

  test("flattening a MetricsTree with multiple items") {
    // not adding a gauge because the gauge won't be returned & it's tricky to test
    // gauge equality...
    val tree = MetricsTree()
    val stat = tree.resolve(Seq("stat")).mkStat(Verbosity.Default)
    val counter = tree.resolve(Seq("counter")).mkCounter(Verbosity.Default)
    MetricsTree.flatten(tree) should contain only (
      "stat" -> stat, "counter" -> counter
    )
  }

  test("flattening a MetricsTree with multiple scopes") {
    // TODO: ScalaCheck test with arbitrary metrics trees?
    val tree = MetricsTree()
    val scope = tree.resolve(Seq("this","is","the"))
    val scope1 = scope.resolve(Seq("first"))
    val scope2 = scope.resolve(Seq("second"))
    val stat0 = scope.resolve(Seq("stat")).mkStat(Verbosity.Default)
    val stat1 = scope1.resolve(Seq("stat")).mkStat(Verbosity.Default)
    val counter1 = scope1.resolve(Seq("counter")).mkCounter(Verbosity.Default)
    val stat2 = scope2.resolve(Seq("stat")).mkStat(Verbosity.Default)
    val counter2 = scope2.resolve(Seq("counter")).mkCounter(Verbosity.Default)
    MetricsTree.flatten(tree) should contain only (
      "this/is/the/stat" -> stat0,
      "this/is/the/first/stat" -> stat1,
      "this/is/the/first/counter" -> counter1,
      "this/is/the/second/stat" -> stat2,
      "this/is/the/second/counter" -> counter2
    )
  }

}
