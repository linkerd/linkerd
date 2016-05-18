package io.buoyant.linkerd.clientTls

import com.twitter.finagle.client.AddrMetadataExtraction.AddrMetadata
import com.twitter.finagle.util.LoadService
import com.twitter.finagle.{Addr, Stack}
import io.buoyant.linkerd.TlsClientInitializer
import io.buoyant.test.Exceptions
import org.scalatest.FunSuite

class BoundPathTest extends FunSuite with Exceptions {
  test("sanity") {
    val meta = AddrMetadata(Addr.Metadata("id" -> "/foo/bar"))
    val params = Stack.Params.empty + meta
    val clientPrep = BoundPathConfig(
      None,
      Seq(NameMatcherConfig("/foo/{service}", "{service}")),
      Some(true)
    ).tlsClientPrep
    assert(clientPrep.peerCommonName(params) == Some("bar"))
  }

  test("boundPath throws MatcherError on failed match in strict mode") {
    val meta = AddrMetadata(Addr.Metadata("id" -> "/foo/bar"))
    val params = Stack.Params.empty + meta
    assertThrows[MatcherError] {
      BoundPathConfig(None, Seq(), Some(true)).tlsClientPrep.peerCommonName(params)
    }
  }

  test("service registration") {
    assert(LoadService[TlsClientInitializer].exists(_.isInstanceOf[BoundPathInitializer]))
  }
}
