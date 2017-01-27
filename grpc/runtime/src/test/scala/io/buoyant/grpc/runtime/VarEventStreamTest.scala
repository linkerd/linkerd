package io.buoyant.grpc.runtime

import com.twitter.util._
import io.buoyant.test.FunSuite

class VarEventStreamTest extends FunSuite {

  test("updates until closed") {
    val source = Var[VarEventStream.Ev[Int]](VarEventStream.Val(0))
    val stream = VarEventStream(source)

    val f0 = stream.recv()
    assert(f0.isDefined)
    assert(await(f0).value == 0)

    val f1 = stream.recv()
    assert(!f1.isDefined)
    source() = VarEventStream.Val(1)
    eventually { assert(f1.isDefined) }
    assert(await(f1).value == 1)

    val f2 = stream.recv()
    assert(!f2.isDefined)
    source() = VarEventStream.End(Return(2))
    eventually { assert(f1.isDefined) }
    assert(await(f2).value == 2)

    val f3 = stream.recv()
    assert(await(f3.liftToTry) == Throw(GrpcStatus.Ok()))
  }

  test("drops intermediate states") {
    val source = Var[VarEventStream.Ev[Int]](VarEventStream.Val(0))
    val stream = VarEventStream(source)

    source() = VarEventStream.Val(1)
    val f0 = stream.recv()
    assert(f0.isDefined)
    assert(await(f0).value == 1)

    source() = VarEventStream.Val(2)
    source() = VarEventStream.Val(3)
    val f1 = stream.recv()
    assert(f1.isDefined)
    assert(await(f1).value == 3)

    // doesn't update after receiving an End
    source() = VarEventStream.End(Return(4))
    source() = VarEventStream.Val(5)
    val f2 = stream.recv()
    assert(f2.isDefined)
    assert(await(f2).value == 4)

    val f3 = stream.recv()
    assert(await(f3.liftToTry) == Throw(GrpcStatus.Ok()))
  }
}
