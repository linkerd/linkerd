package io.buoyant.namerd

import com.twitter.conversions.time._
import com.twitter.finagle._
import com.twitter.finagle.http.Method
import com.twitter.finagle.util.DefaultTimer
import com.twitter.server.TwitterServer
import com.twitter.util._
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong
import scala.sys.process.{Process, ProcessLogger}

object Validator extends TwitterServer {

  private[this] implicit val timer = DefaultTimer

  val namerdExec = flag("namerd.exec", "", "Path to namerd executable")
  val linkerdExec = flag("linkerd.exec", "", "Path to linkerd executable")
  val nServers = flag("servers.count", 3, "Number of servers to cycle through")
  val testDuration = flag("duration", 1.minute, "Amount of time requests should be sent through the router")

  val namerdNs = "validation"
  val hostName = "skrrt"
  val baseDtab = Dtab.read("/svc => /host ;")

  def await[T](a: Awaitable[T], d: Duration = 10.seconds): T = Await.result(a, d)

  case class AssertionFailed(value: Any, expected: Seq[Any])
    extends Throwable(s"$value was not in $expected")

  def assertEq[T](a: T, bs: T*): Unit = if (!bs.contains(a)) {
    val e = new AssertionFailed(a, bs)
    log.error(e, s"$a is not in $bs")
    throw e
  }

  def main(): Unit = {
    if (namerdExec().isEmpty || linkerdExec().isEmpty) {
      exitOnError(s"-${namerdExec.name} and -${linkerdExec.name} must be specified")
    }

    def mkCmd(exec: String, f: String) =
      exec :: "-com.twitter.finagle.tracing.debugTrace=true" ::
        "-log.level=DEBUG" :: f :: Nil

    nServers() match {
      case n if n <= 0 =>
        exitOnError(s"-${nServers.name} must be positive")

      case count =>
        val counters = (0 until count).map { i => i -> new AtomicLong(0) }.toMap
        val servers = (0 until count).map { i =>
          val counter = counters(i)
          val server = Http.serve(":*", Service.mk[http.Request, http.Response] { req =>
            counter.incrementAndGet()

            val rsp = http.Response()
            rsp.contentString = i.toString
            Future.value(rsp)
          })
          closeOnExit(server)
          server
        }
        val ports = servers.map(_.boundAddress.asInstanceOf[InetSocketAddress].getPort)

        val namerdClient = Http.client
          .withSessionQualifier.noFailFast
          .withSessionQualifier.noFailureAccrual
          .newService("/$/inet/127.1/4180")
        closeOnExit(namerdClient)

        sealed trait RouteState
        object NoRoute extends RouteState
        case class RouteTo(instance: Int, previous: Option[Int], since: Time) extends RouteState
        @volatile var routeState: RouteState = NoRoute
        def nextState = routeState match {
          case NoRoute => RouteTo(0, None, Time.now)
          case RouteTo(i, _, _) => RouteTo((i + 1) % count, Some(i), Time.now)
        }

        def withNamerd(namerdConfigFile: String)(f: Killer => Unit): Unit =
          withRunning("namerd", 9001, mkCmd(namerdExec(), namerdConfigFile)) { kill =>
            log.info("waiting for 10s for startup...")
            Thread.sleep(10000)
            f(kill)
          }

        def createDtabNamespace(): Future[Unit] = {
          val req = http.Request()
          req.method = http.Method.Post
          req.host = "namerd"
          req.uri = s"/api/1/dtabs/$namerdNs"
          req.contentString = ""
          req.contentType = "application/dtab"
          val next = nextState
          namerdClient(req).map { rsp =>
            routeState = next
            log.info("updated route %s", next)
            assertEq(rsp.status, http.Status.NoContent)
          }
        }

        def doUpdate(): Future[Unit] = {
          val getReq = http.Request()
          getReq.host = "namerd"
          getReq.uri = s"/api/1/dtabs/$namerdNs"

          namerdClient(getReq).map { rsp =>
            assert(rsp.status == http.Status.Ok)
            rsp.headerMap("Etag")
          }.flatMap { version =>
            val next = nextState
            val dtab = baseDtab ++ Dtab.read(s"/host/$hostName => /$$/inet/127.1/${ports(next.instance)}")
            log.info("updating dtab for %d to %s", next.instance, dtab.show)
            val req = http.Request()
            req.method = http.Method.Put
            req.host = "namerd"
            req.uri = s"/api/1/dtabs/$namerdNs"
            req.headerMap.add("If-Match", version)
            req.contentString = dtab.show
            req.contentType = "application/dtab"
            namerdClient(req).map { rsp =>
              routeState = next
              assertEq(rsp.status, http.Status.NoContent)
            }
          }
        }

        val linkerdClient = Http.newService("/$/inet/127.1/4140")
        closeOnExit(linkerdClient)

        def doRoute(): Future[Unit] = {
          routeState match {
            case NoRoute => log.info("routing an error")
            case RouteTo(i, _, _) => log.info("routing %d to %s", i, ports(i))
          }

          val req = http.Request()
          req.host = hostName
          linkerdClient(req).map { rsp =>
            routeState match {
              case NoRoute =>
                assertEq(rsp.status, http.Status.BadGateway)

              case RouteTo(i, prevI, since) =>
                // If an update was fairly recent, give a little leeway
                if (Time.now - since < 3.seconds) {
                  prevI match {
                    case None =>
                      assertEq(rsp.status, http.Status.Ok, http.Status.BadGateway)
                      if (rsp.status == http.Status.Ok) assertEq(rsp.contentString, i.toString)

                    case Some(prevI) =>
                      assertEq(rsp.status, http.Status.Ok)
                      assertEq(rsp.contentString, i.toString, prevI.toString)
                  }
                } else {
                  assertEq(rsp.status, http.Status.Ok)
                  assertEq(rsp.contentString, i.toString)
                }
            }
          }
        }

        def routeLoop(): Future[Unit] = {
          @volatile var donezo = false
          Future.sleep(testDuration()).ensure { donezo = true }
          def loop(): Future[Unit] =
            if (donezo) Future.Unit
            else doRoute().before(Future.sleep(1.second)).before(loop())
          loop()
        }

        def updateLoop(duration: Duration, pause: Duration = 1.second): Future[Unit] = {
          @volatile var donezo = false
          Future.sleep(duration).ensure {
            donezo = true
          }
          def loop(i: Int): Future[Unit] =
            if (donezo) Future.Unit
            else doUpdate().before(Future.sleep(pause)).before(loop(i + 1))
          createDtabNamespace().before(loop(0))
        }

        /*
         * ACTUALLY RUN THE THING NOW WOOOOOOO
         */

        try {
          withTmpDir { tmpdir =>
            val linkerdConfigFile = s"$tmpdir/l5d.yml"
            writeFile(mkLinkerdConfig("/$/inet/127.1/4100", namerdNs, 4140, 9002), linkerdConfigFile)
            withRunning("linkerd", 9002, mkCmd(linkerdExec(), linkerdConfigFile)) { killLinkerd =>
              val namerdConfigFile = s"$tmpdir/namerd.yml"
              writeFile(mkNamerdConfig(4180, 4100, 9001), namerdConfigFile)

              // start namerd
              withNamerd(namerdConfigFile) { killNamerd =>
                // send request through the router for testDuration
                val requests = routeLoop()
                val updates = updateLoop(testDuration(), 5.seconds)
                // continually update routing
                await(requests join updates, testDuration() + 10.seconds)

                // when that's all done and well, kill namerd
                killNamerd()
                // linkerd should continue to route requests
                await(doRoute())
                log.info("restarting namerd")
                // then we start namerd agaain
                withNamerd(namerdConfigFile) { killNamerd =>
                  // linkerd should be able to observe an update (after reconnecting)
                  Thread.sleep(10.seconds.inMillis)
                  await(createDtabNamespace.before(doUpdate()))
                  Thread.sleep(10.seconds.inMillis)
                  await(doRoute())

                  // then we're all done
                  killLinkerd()
                  killNamerd()
                  log.info("donezo!")
                }
              }
            }
          }
        } finally {
          await(linkerdClient.close() join namerdClient.close())
          val _ = await(Future.collect(servers.map(_.close())))
        }
    }
  }

  def writeFile(body: String, path: String): Unit = {
    val file = new java.io.File(path)
    val bw = new java.io.BufferedWriter(new java.io.FileWriter(file))
    bw.write(body)
    bw.close()
  }

  def mkLinkerdConfig(dst: String, ns: String, port: Int, adminPort: Int): String =
    s"""admin:
       |  port: $adminPort
       |
       |routers:
       |- protocol: http
       |  servers:
       |  - port: $port
       |  interpreter:
       |    kind: io.l5d.namerd
       |    dst: $dst
       |    namespace: $ns
       |""".stripMargin

  def mkNamerdConfig(controllerPort: Int, serverPort: Int, adminPort: Int): String =
    s"""storage:
       |  kind: io.l5d.inMemory
       |namers:
       |- kind: io.l5d.fs
       |  rootDir: namerd/examples/disco
       |admin:
       |  port: $adminPort
       |interfaces:
       |- kind: io.l5d.thriftNameInterpreter
       |  ip: 0.0.0.0
       |  port: $serverPort
       |- kind: io.l5d.httpController
       |  ip: 0.0.0.0
       |  port: $controllerPort
       |""".stripMargin

  def withTmpDir(f: String => Unit): Unit = {
    val tmpdir = Process("mktemp" :: "-d" :: "-t" :: "v4l1d4t0r.XXXXX" :: Nil).!!.stripLineEnd
    try f(tmpdir) finally { val _ = Process("rm" :: "-rf" :: tmpdir :: Nil).! }
  }

  type Killer = () => Unit

  def withRunning(name: String, adminPort: Int, cmd: Seq[String])(f: Killer => Unit): Unit = {
    log.info("%s: `%s`", name, cmd.mkString(" "))
    val out = ProcessLogger(log.info("%s: %s", name, _))
    val proc = Process(cmd).run(out)
    val admin = Http.client
      .withSessionQualifier.noFailFast
      .withSessionQualifier.noFailureAccrual
      .newService(s"/$$/inet/127.1/$adminPort")

    val mu = new {}
    var killed = false
    def kill() = mu.synchronized {
      if (!killed) {
        log.info(s"killing $name")
        val req = http.Request(Method.Post, "/admin/shutdown")
        await(admin(req).liftToTry)
        admin.close() // don't bother awaiting
        Thread.sleep(5.seconds.inMillis)
        proc.destroy()
        killed = true
      }
    }

    try f(() => kill()) finally kill()
  }
}
