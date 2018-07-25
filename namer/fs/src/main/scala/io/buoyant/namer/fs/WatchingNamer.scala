package io.buoyant.namer.fs

import com.twitter.finagle._
import com.twitter.finagle.addr.WeightedAddress
import com.twitter.finagle.http.{MediaType, Request, Response}
import com.twitter.io.Buf
import com.twitter.logging.Logger
import com.twitter.util._
import io.buoyant.admin.Admin
import io.buoyant.config.Parser
import io.buoyant.namer.EnumeratingNamer
import java.nio.file.{Path => NioPath}

object WatchingNamer {
  private val log = Logger.get(getClass.getName)

  case class MalformedAddress(text: String)
    extends IllegalArgumentException(s"malformed address: $text")

  private val Commentless = """^([^#]*)(?:#.*)?""".r
  private def txtToAddr(txt: String): Addr = {
    val lines = txt.split('\n').map { case Commentless(ln) => ln.trim }.filter(_.nonEmpty)
    Try.collect(lines.map(txtToAddress)) match {
      case Return(addrs) => Addr.Bound(addrs.toSet, Addr.Metadata.empty)
      case Throw(e) => Addr.Failed(e)
    }
  }

  private object PortNum {
    val Max = math.pow(2, 16) - 1
    def unapply(s: String): Option[Int] =
      Try(s.toInt).toOption.filter { p => (0 < p && p <= Max) }
  }

  private object WeightNum {
    def unapply(s: String): Option[Double] = Try(s.toDouble).toOption
  }

  private[this] val Whitespace = """\s+""".r

  /**
   * lines are in the format:
   *   host port
   */
  private def txtToAddress(txt: String): Try[Address] = Whitespace.split(txt) match {
    case Array(host, PortNum(port)) => Try(Address(host, port))
    case Array(host, PortNum(port), "*", WeightNum(weight)) => Try(WeightedAddress(Address(host, port), weight))
    case _ => Throw(MalformedAddress(txt))
  }
}

class WatchingNamer(rootDir: NioPath, prefix: Path) extends EnumeratingNamer with Admin.WithHandlers {
  import WatchingNamer._

  private[this] lazy val root = Watcher(rootDir)

  def lookup(path: Path): Activity[NameTree[Name]] =
    root.children.underlying.flatMap(lookup(prefix, path, _))

  override def getAllNames: Activity[Set[Path]] =
    root.children.underlying.map { children =>
      children.keySet.map { child =>
        prefix ++ Path.Utf8(child)
      }
    }

  override val adminHandlers: Seq[Admin.Handler] = {
    val p = prefix.drop(1).show.drop(1) // drop leading "/#/"
    Seq(Admin.Handler(s"/namer_state/$p.json", new WatchingNamerStateHandler(root)))
  }

  /** Recursively resolve `path` in the given directory. */
  private[this] def lookup(
    prefix: Path,
    path: Path,
    children: Watcher.File.Children
  ): Activity[NameTree[Name]] = path.take(1) match {
    case phd@Path.Utf8(name) =>
      val id = prefix ++ phd
      val residual = path.drop(1)
      log.debug("fs lookup %s %s %s", prefix.show, name, residual.show)

      children.get(name) match {
        case Some(Watcher.File.Reg(data)) =>
          log.debug("fs lookup %s file %s", prefix.show, name)

          val addr: Var[Addr] = data.run.map {
            case Activity.Pending => Addr.Pending
            case Activity.Failed(e) => Addr.Failed(e)
            case Activity.Ok(buf@Buf.Utf8(txt)) =>
              log.debug("fs lookup %s addr %s %d bytes", prefix.show, name, buf.length)
              txtToAddr(txt)
          }

          Activity.value(NameTree.Leaf(Name.Bound(addr, id, residual)))

        case Some(Watcher.File.Dir(children, _)) =>
          log.debug("fs lookup %s dir %s", prefix.show, name)
          children.underlying.flatMap(lookup(id, residual, _))

        case None =>
          log.debug("fs lookup %s missing %s", prefix.show, name)
          Activity.value(NameTree.Neg)
      }

    case _ => Activity.value(NameTree.Neg)
  }
}

class WatchingNamerStateHandler(
  root: => Watcher.File.Dir
) extends Service[Request, Response] {
  private[this] val mapper = Parser.jsonObjectMapper(Nil)

  def render(dir: Watcher.File.Dir): Map[String, Any] = {
    val meta = Map(
      "events" -> dir.state,
      "state" -> dir.children.stateSnapshot()
    )
    val children = dir.children.value match {
      case Activity.Ok(c) => c.collect {
        case (child, file: Watcher.File.Dir) => (child, render(file))
      }
      case _ => Map.empty
    }
    meta ++ children
  }

  override def apply(request: Request): Future[Response] = {

    val json = mapper.writeValueAsString(render(root))

    val res = Response()
    res.mediaType = MediaType.Json
    res.contentString = json
    Future.value(res)
  }
}
