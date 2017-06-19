package io.buoyant.etcd

import com.twitter.finagle.Http
import com.twitter.finagle.util.DefaultTimer
import io.buoyant.test.Awaits
import java.io.File
import java.util.UUID
import org.scalatest.BeforeAndAfterAll
import org.scalatest.fixture.FunSuite
import scala.sys.process.{Process, ProcessLogger}
import scala.util.Random

class EtcdFixture extends FunSuite with Awaits with BeforeAndAfterAll {

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
    val _ = Process(Seq("rm", "-rf", etcdDir)).!
  }

  def serverName = s"/$$/inet/127.1/$etcdPort"

  private[this] implicit val timer = DefaultTimer

  type FixtureParam = Etcd

  def withFixture(test: OneArgTest) = {
    val client = Http.newService(serverName)
    try withFixture(test.toNoArgTest(new Etcd(client)))
    finally await { client.close() }
  }
}
