package io.buoyant.etcd

import com.twitter.conversions.DurationOps._
import com.twitter.finagle.Path
import com.twitter.io.Buf
import com.twitter.util.{Events => _}
import java.util.UUID

/**
 * Etcd client integration tests.
 *
 * Boots an etcd instance on a random local port.
 */
class EtcdIntegrationTest extends EtcdFixture {

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

}
