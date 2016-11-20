package io.buoyant.router
package h2

import com.twitter.finagle.{Dtab, Path}
import com.twitter.finagle.buoyant.Dst
import com.twitter.logging.Level
import com.twitter.util.Future
import io.buoyant.test.FunSuite
import java.net.InetSocketAddress

class RouterEndToEndTest
  extends FunSuite
  with ClientServerHelpers {

  test("router with prior knowledge") {
    // setLogLevel(Level.DEBUG)
    val cat = Downstream.const("cat", "meow")
    val dog = Downstream.const("dog", "woof")
    val dtab = Dtab.read(s"""
        /p/cat => /$$/inet/127.1/${cat.port} ;
        /p/dog => /$$/inet/127.1/${dog.port} ;

        /h2/felix    => /p/cat ;
        /h2/clifford => /p/dog ;
      """)
    val identifierParam = H2.Identifier { _ =>
      req => {
        val dst = Dst.Path(Path.Utf8("h2", req.authority), dtab)
        Future.value(new RoutingFactory.IdentifiedRequest(dst, req))
      }
    }
    val router = H2.serve(new InetSocketAddress(0), H2.router
      .configured(identifierParam)
      .factory())
    val client = upstream(router)
    try {
      client.get("felix")(_ == Some("meow"))
      client.get("clifford", "/the/big/red/dog")(_ == Some("woof"))
    } finally {
      setLogLevel(Level.OFF)
      await(client.close())
      await(cat.server.close())
      await(dog.server.close())
      await(router.close())
    }
  }
}
