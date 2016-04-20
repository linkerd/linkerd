package io.buoyant.etcd

import com.twitter.conversions.time._
import com.twitter.finagle.{Http, Path}
import com.twitter.finagle.util.DefaultTimer
import com.twitter.io.Buf
import com.twitter.util.{Events => _, _}
import io.buoyant.test.{Awaits, Events}
import java.io.File
import java.util.UUID
import org.scalatest.BeforeAndAfterAll
import org.scalatest.fixture.FunSuite
import scala.sys.process.{Process, ProcessLogger}
import scala.util.Random

/**
 * Etcd client integration tests.
 *
 * Boots an etcd instance on localhost:4001.
 */
class EtcdIntegrationTest extends FunSuite with Awaits with BeforeAndAfterAll {

  lazy val devNull = new File("/dev/null")
  lazy val etcdDir = s"/tmp/io.buoyant.etcd-${UUID.randomUUID.toString}"
  def randomPort = 32000 + (Random.nextDouble * 30000).toInt
  lazy val etcdPort = randomPort
  lazy val etcdUrl = s"http://127.0.0.1:$etcdPort"
  lazy val etcdPeerUrl = s"http://127.0.0.1:$randomPort"
  lazy val etcdCmd = Seq(
    "etcd",
    "--data-dir", etcdDir,
    "--listen-client-urls", etcdUrl,
    "--advertise-client-urls", etcdUrl,
    "--listen-peer-urls", etcdPeerUrl,
    "--initial-advertise-peer-urls", etcdPeerUrl,
    "--initial-cluster", s"default=$etcdPeerUrl",
    "--force-new-cluster"
  )

  var process: Process = _

  override def beforeAll: Unit = {
    val which = Process(Seq("which", "etcd")).run(ProcessLogger(devNull))
    which.exitValue match {
      case 0 =>
        info(s"""${etcdCmd mkString " "}""")
        try {
          process = Process(etcdCmd).run(ProcessLogger(devNull))
        } catch {
          case e: Exception => fail(s"etcd failed to start: ${e.getMessage}")
        }
        Thread.sleep(5000) // give some time to initialize

      case _ => cancel("etcd not on the PATH")
    }
  }

  override def afterAll: Unit = {
    if (process != null) {
      process.destroy()
    }
    Process(Seq("rm", "-rf", etcdDir)).!
  }

  def serverName = s"/$$/inet/127.1/$etcdPort"

  private[this] implicit val timer = DefaultTimer.twitter
  override def defaultWait = 1.second

  type FixtureParam = Etcd

  def withFixture(test: OneArgTest) = {
    val client = Http.newService(serverName)
    try withFixture(test.toNoArgTest(new Etcd(client)))
    finally await { client.close() }
  }


  test("version") { etcd =>
    val version = await { etcd.version() }

    assert(version.etcdserver.length > 0)
    info(s"release: ${version.etcdserver}")

    assert(version.etcdcluster.length > 0)
    info(s"internal: ${version.etcdcluster}")
  }

  val rootPath = Path.Utf8("it")

  test("create and delete data node") { etcd =>
    val key = etcd.key(rootPath)
    val value = UUID.randomUUID().toString

    val createOp = await { key.create(Some(Buf.Utf8(value))) }
    assert(createOp.action == NodeOp.Action.Create)
    createOp.node match {
      case createData@Node.Data(createKey, createModified, createCreated, None, createVal) =>
        assert(createKey.take(1) == key.path)
        assert(createModified == createCreated)
        assert(createModified == createOp.etcd.index)
        assert(createVal == Buf.Utf8(value))
        info("create data")

        val delOp = await { etcd.key(createKey).delete() }
        assert(delOp.action == NodeOp.Action.Delete)
        delOp.node match {
          case Node.Data(delKey, delModified, delCreated, None, delVal) =>
            assert(delKey == createKey)
            assert(delModified > createModified)
            assert(delCreated == createCreated)
            assert(delVal == Buf.Empty)

          case node =>
            fail(s"expected deleted data node, found $node")
        }
        assert(createOp.etcd.index < delOp.etcd.index)
        assert(createOp.etcd.clusterId == delOp.etcd.clusterId)

        delOp.prevNode match {
          case Some(Node.Data(prevKey, prevModified, prevCreated, None, prevVal)) =>
            assert(prevKey == createKey)
            assert(prevModified == createModified)
            assert(prevCreated == createCreated)
            assert(prevVal == Buf.Utf8(value))
          case node =>
            fail(s"expected previous data node, found $node")
        }
        info("deleted key")

      case node =>
        fail(s"expected set data node, found $node")
    }
  }

  test("get(wait), set(ttl), get(wait, idx), expire") { etcd =>
    val key = etcd.key(Path.Utf8(UUID.randomUUID().toString))

    info("waiting for data")
    val wait = key.get(wait = true)

    assert(!wait.isDefined)

    val woof = Buf.Utf8("woof woof woof")
    val ttl = 10.seconds
    val created = await { key.set(value = Some(woof), ttl=Some(ttl)) }
    assert(created.action == NodeOp.Action.Set)
    created.node match {
      case Node.Data(path, _, _, lease, value) =>
        assert(path == key.path)
        assert(lease.isDefined)
        assert(value == woof)
        info("setting data")

      case node =>
        fail(s"setting data node, found $node")
    }

    val set = await(wait)
    assert(set.action == NodeOp.Action.Set)
    set.node match {
      case Node.Data(path, _, _, lease, value) =>
        assert(path == created.node.key)
        assert(lease.isDefined)
        assert(lease.map(_.expiration) == created.node.lease.map(_.expiration))
        assert(value == woof)
      case node =>
        fail(s"observing data $node")
    }

    info("waiting for expiration")
    val expire = await(ttl+1.second) {
      key.get(wait = true, waitIndex = Some(set.node.modifiedIndex+1))
    }
    assert(expire.action == NodeOp.Action.Expire)
    expire.node match {
      case Node.Data(path, _, _, None, Buf.Empty) =>
        assert(path == created.node.key)
      case node =>
        fail(s"expecting expired data node, found $node")
    }
    assert(expire.prevNode.isDefined)
    val Some(prev) = expire.prevNode
    prev match {
      case Node.Data(path, _, _, lease, value) =>
        assert(path == created.node.key)
        assert(lease == None)
        assert(value == woof)
      case node =>
        fail(s"expecting expired data node, found $node")
    }
  }

  test("watch(wait), create(ttl)") { etcd =>
    val key = etcd.key(Path.Utf8(java.util.UUID.randomUUID().toString))
    val bowwow = Buf.Utf8("bow wow wow")
    val ttl = 10.seconds

    val events = Events.takeStates(4, key.watch())
    await { events.next() } match {
      case (Activity.Pending, events) =>
        info("connecting")

        await { events.next() } match {
          case (Activity.Failed(ApiError(ApiError.KeyNotFound, _,_,_)), events) =>
            info("node does not yet exist")

            val created = await {
              key.set(value = Some(bowwow), ttl = Some(ttl))
            }
            assert(created.action == NodeOp.Action.Set)
            created.node match {
              case Node.Data(path, _, _, lease, value) =>
                assert(path == key.path)
                assert(lease.isDefined)
                assert(value == bowwow)
              case node =>
                fail(s"setting data node, found $node")
            }
            assert(created.prevNode == None)
            info("set data node")

            await { events.next() } match {
              case (Activity.Ok(NodeOp(NodeOp.Action.Set, Node.Data(path, _, _, lease, value), _, None)), events) =>
                assert(path == created.node.key)
                assert(lease.isDefined)
                assert(lease.map(_.expiration) == created.node.lease.map(_.expiration))
                assert(value == bowwow)
                info("observed set data node")

                // wait for the ttl to elapse
                // we add a second since etcd only has second granularity and may not fire the event
                // if the second hasn't elapsed ;/
                info("waiting for ttl to expire")
                Thread.sleep((ttl + 1.second).inMillis)

                await { events.next() } match {
                  case (Activity.Ok(NodeOp(act, node: Node.Data, _, Some(prior: Node.Data))), _) =>
                    assert(act == NodeOp.Action.Expire)
                    assert(node.key == created.node.key)
                    assert(prior.key == node.key)
                    assert(prior.value == bowwow)
                    assert(prior.modifiedIndex < node.modifiedIndex)
                    assert(prior.createdIndex == node.createdIndex)
                    info("expired")

                  case state =>
                    fail(s"observing expiration, found $state")
                }
              case state =>
                fail(s"observing data, found $state")
            }
          case (state, _) =>
            fail(s"checking that node doesn't exist, found $state")
        }
      case (state, _) =>
        fail(s"unexpected state: $state")
    }
  }

}
