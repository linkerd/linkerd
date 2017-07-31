package io.buoyant.test

import com.twitter.finagle.stats.InMemoryStatsReceiver
import org.scalatest.{Assertion, Assertions, OptionValues}

trait StatsAssertions extends OptionValues {

  def assertStat(name: Seq[String])(implicit stats: InMemoryStatsReceiver) = new StatQuery(name, stats)
  def assertCounter(name: Seq[String])(implicit stats: InMemoryStatsReceiver) = new CounterQuery(name, stats)

  abstract class AssertableQuery protected[this] (val stat: Seq[String], val kind: String)
    extends Assertions
    with OptionValues {
    type Stat
    protected[this] val clue = s"$kind ${stat.mkString("/")}: "
    protected[this] def get: Option[Stat]

    @inline final def isEmpty: Assertion =
      withClue(clue) { assert(get.isEmpty, s", actual value was $get") }
    @inline final def isDefined: Assertion =
      withClue(clue) { assert(get.isDefined) }
    @inline final def is(value: Stat): Assertion =
      withClue(clue) { assert(get.value == value) }
    @inline final def isNot(value: Stat): Assertion =
      withClue(clue) { assert(get.value != value) }
    @inline final def matches(predicate: Stat => Boolean): Assertion =
      withClue(clue) { assert(predicate(get.value)) }
  }

  class CounterQuery(stat: Seq[String], stats: InMemoryStatsReceiver)
    extends AssertableQuery(stat, "counter") {
    override type Stat = Int
    override protected[this] def get: Option[Stat] = stats.counters.get(stat)
  }

  class StatQuery(stat: Seq[String], stats: InMemoryStatsReceiver)
    extends AssertableQuery(stat, "stat") {
    override type Stat = Seq[Float]
    override protected[this] def get: Option[Stat] = stats.stats.get(stat)

    @inline final def contains(value: Float): Assertion =
      withClue(clue) { assert(get.value.contains(value)) }

    @inline final def doesNotContain(value: Float): Assertion =
      withClue(clue) { assert(!get.value.contains(value)) }

    @inline final def hasLength(length: Int): Assertion =
      withClue(clue) { assert(get.value.length == length) }

    @inline final def forall(predicate: Float => Boolean): Assertion =
      withClue(clue) { assert(get.value.forall(predicate)) }

  }

}
