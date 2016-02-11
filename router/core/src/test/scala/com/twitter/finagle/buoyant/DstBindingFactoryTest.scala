package com.twitter.finagle.buoyant

import com.twitter.finagle._
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.util.{Activity, Future, Time, Var}
import io.buoyant.test.Awaits
import java.net.SocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import org.scalatest.FunSuite

class DstBindingFactoryTest extends FunSuite with Awaits {

  object TestSockAddr extends SocketAddress

  test("Cached: caches residuals independently") {
    var creates = Map.empty[String, Int]

    def mkClient(bound: Name.Bound): ServiceFactory[String, String] = synchronized {
      val count = creates.getOrElse(bound.idStr, 0) + 1
      creates = creates + (bound.idStr -> count)
      val svc = Service.const(Future.value(bound.idStr))
      ServiceFactory.const(svc)
    }

    def boundFilter(bound: Dst.Bound) =
      Filter.mk[String, String, String, String] { (in, svc) =>
        svc(in).map { out => s"${bound.path.show} on $out" }
      }

    val namer = new NameInterpreter {
      def bind(dtab: Dtab, path: Path): Activity[NameTree[Name.Bound]] = path match {
        case Path.Utf8(_, _*) =>
          val addr = Var.value(Addr.Bound(TestSockAddr))
          Activity.value(NameTree.Leaf(Name.Bound(addr, path.take(1), path.drop(1))))

        case _ => fail("invalid destination: ${path.show}")
      }
    }

    val cache = new DstBindingFactory.Cached[String, String](
      mkClient,
      boundMk = (d: Dst.Bound, f: ServiceFactory[String, String]) => boundFilter(d).andThen(f),
      namer = namer
    )

    assert(creates.getOrElse("/usa", 0) == 0)
    val client0 = await(cache(Dst.Path(Path.read("/usa/ca/sf"), Dtab.empty, Dtab.empty)))
    assert(creates.getOrElse("/usa", 0) == 1)
    assert(await(client0("bob")) == "/ca/sf on /usa")

    val client1 = await(cache(Dst.Path(Path.read("/usa/ca/la"), Dtab.empty, Dtab.empty)))
    assert(creates.getOrElse("/usa", 0) == 1)
    assert(await(client1("bob")) == "/ca/la on /usa")

    assert(creates.getOrElse("/america", 0) == 0)
    val client2 = await(cache(Dst.Path(Path.read("/america/ca/la"), Dtab.empty, Dtab.empty)))
    assert(creates.getOrElse("/america", 0) == 1)
    assert(creates.getOrElse("/usa", 0) == 1)
    assert(await(client2("bob")) == "/ca/la on /america")
  }

  test("RefCounted: reference-counted rpc client factory") {
    val closed = new AtomicBoolean(false)
    val factory = DstBindingFactory.refcount {
      new DstBindingFactory[String, String] {
        def apply(dst: Dst, conn: ClientConnection): Future[Service[String, String]] = ???
        def status = Status.Open
        def close(t: Time): Future[Unit] = {
          closed.set(true)
          Future.Unit
        }
      }
    }

    assert(factory.references == 0)
    assert(!closed.get)

    val c0 = factory.acquire()
    assert(factory.references == 1)
    assert(!closed.get)

    val c1 = factory.acquire()
    assert(factory.references == 2)
    assert(!closed.get)

    await(c0.close())
    await(c0.close()) // nop
    assert(factory.references == 1)
    assert(!closed.get)

    await(c1.close())
    assert(factory.references == 0)
    assert(closed.get)

    intercept[IllegalStateException] {
      factory.acquire()
    }

    await(c1.close()) // nop
  }

  test("Ignores cached NoBrokersAvailableException info when propagating the exception") {
    def mkClient(bound: Name.Bound): ServiceFactory[String, String] = synchronized {
      val svc = Service.const(Future.value(bound.idStr))
      ServiceFactory.const(svc)
    }

    val namer = new NameInterpreter {
      def bind(dtab: Dtab, path: Path): Activity[NameTree[Name.Bound]] = Activity.value(NameTree.Neg)
    }

    // copy-pasted from NameTreeFactory
    case class Failed(exn: Throwable) extends ServiceFactory[String, String] {
      val service: Future[Service[String, String]] = Future.exception(exn)
      override def status = Status.Closed
      def apply(conn: ClientConnection) = service
      def close(deadline: Time) = Future.Done
    }

    val cache = new DstBindingFactory.Cached[String, String](
      mkClient,
      boundMk = (d: Dst.Bound, f: ServiceFactory[String, String]) => Failed(new NoBrokersAvailableException("")),
      namer = namer
    )

    val path1exception = intercept[NoBrokersAvailableException] {
      await(cache(Dst.Path(Path.read("/usa/ca/la"), Dtab.empty, Dtab.empty)))
    }

    val path2exception = intercept[NoBrokersAvailableException] {
      await(cache(Dst.Path(Path.read("/usa/ca/sf"), Dtab.empty, Dtab.empty)))
    }

    assert(path1exception.name == "/usa/ca/la")
    assert(path2exception.name == "/usa/ca/sf")
  }
}
