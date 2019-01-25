package io.buoyant.namerd.iface.mesh

import com.google.protobuf.CodedOutputStream
import com.twitter.finagle._
import io.buoyant.namer.DelegateTree
import io.buoyant.test.FunSuite
import io.linkerd.mesh.DelegateTreeRsp
import java.io.ByteArrayOutputStream

class DelegatorServiceTest extends FunSuite {
  test("Delegator service responding with DelegateTree Exception shouldn't throw a null pointer exception"){
    val out = CodedOutputStream.newInstance(new ByteArrayOutputStream())
    DelegateTreeRsp.codec.encode(DelegatorService.toDelegateTreeRsp(DelegateTree.Exception(Path.read("/svc"), Dentry.nop, new Throwable)), out)
    assert(out.getTotalBytesWritten > 0)
  }
}
