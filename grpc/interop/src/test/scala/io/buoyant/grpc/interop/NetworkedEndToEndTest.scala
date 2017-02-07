package io.buoyant.grpc.interop

import com.twitter.conversions.storage._
import com.twitter.finagle.buoyant.{H2, h2}
import com.twitter.util.Future
import grpc.{testing => pb}
import io.buoyant.test.FunSuite
import java.net.InetSocketAddress

class NetworkedInteropTest extends FunSuite with InteropTestBase {

  // override def only = Set("large_unary")

  val autoRefillConnectionWindow = h2.param.FlowControl.AutoRefillConnectionWindow(true)
  val initialWindowSize = h2.param.Settings.InitialStreamWindowSize(Some(1.megabyte))

  override def withClient(run: Client => Future[Unit]): Future[Unit] = {
    val s = H2.server
      .configured(autoRefillConnectionWindow)
      .configured(initialWindowSize)
      .serve("127.1:*", (new Server).dispatcher)

    val c = {
      val addr = s.boundAddress.asInstanceOf[InetSocketAddress]
      H2.client
        .configured(autoRefillConnectionWindow)
        .configured(initialWindowSize)
        .newService(s"/$$/inet/127.1/${addr.getPort}")
    }
    val client = new Client(new pb.TestService.Client(c))

    // setLogLevel(com.twitter.logging.Level.ALL)
    run(client).transform { ret =>
      // setLogLevel(com.twitter.logging.Level.OFF)
      c.close().join(s.close()).unit.before(Future.const(ret))
    }
  }
}
