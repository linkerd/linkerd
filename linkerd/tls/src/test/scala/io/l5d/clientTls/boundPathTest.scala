package io.l5d.clientTls

import com.twitter.finagle.{Addr, Stack}
import com.twitter.finagle.client.AddrMetadataExtraction.AddrMetadata
import com.twitter.finagle.util.LoadService
import io.buoyant.linkerd.TlsClientInitializer
import org.scalatest.FunSuite

class boundPathTest extends FunSuite {
  test("sanity") {
    static("hello", None).tlsClientPrep
  }

  test("boundPath throws MatcherError on failed match in strict mode") {
    val meta = AddrMetadata(Addr.Metadata("id" -> "/foo/bar"))
    val params = Stack.Params.empty + meta
    intercept[MatcherError] {
      boundPath(None, Seq(), Some(true)).tlsClientPrep.peerCommonName(params)
    }
  }

  test("service registration") {
    assert(LoadService[TlsClientInitializer].exists(_.isInstanceOf[BoundPathInitializer]))
  }
}
