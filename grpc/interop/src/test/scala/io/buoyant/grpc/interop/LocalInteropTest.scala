package io.buoyant.grpc.interop

import com.twitter.util.Future
import io.buoyant.test.FunSuite

class LocalInteropTest extends FunSuite with InteropTestBase {

  override def withClient(f: Client => Future[Unit]): Future[Unit] =
    f(new Client(new Server))
}
