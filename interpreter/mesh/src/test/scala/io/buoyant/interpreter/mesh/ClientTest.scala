package io.buoyant.interpreter.mesh

import com.twitter.finagle.{Dtab, Name, NameTree, Path}
import com.twitter.util.Promise
import io.buoyant.grpc.runtime.{GrpcStatus, Stream}
import io.buoyant.test.FunSuite
import io.linkerd.mesh

class ClientTest extends FunSuite {

  test("bind") {
    val reqP = new Promise[mesh.BindReq]
    val rsps = Stream.mk[mesh.BoundTreeRsp]
    val interp = new mesh.Interpreter {
      def getBoundTree(req: mesh.BindReq) =
        Future.exception(GrpcStatus.Unimplemented())

      def streamBoundTree(req: mesh.BindReq) = {
        reqP.setValue(req)
        rsps
      }
    }
    val client = Client("foons", interp, ???, ???)

    val act = client.bind(Dtab.read("/extra => /stuff"), Path.read("/some/name"))

    @volatile var state: Activity.State[NameTree[Name.Bound]] = Activity.Pending
    val responding = act.states.respond(state = _)
    try {
      assert(state == Activity.Pending)
      val sendF = rsps.send(mesh.BoundTreeRsp(
        tree = Some()
      ))

    } finally await(responding.close())
  }
}
