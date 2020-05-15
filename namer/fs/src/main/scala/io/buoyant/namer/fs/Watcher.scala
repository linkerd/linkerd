package io.buoyant.namer.fs

import com.twitter.conversions.StorageUnitOps._
import com.twitter.io.Buf
import com.twitter.logging.Logger
import com.twitter.util._
import io.buoyant.namer.InstrumentedActivity
import java.nio.file.{Path => NioPath, _}
import java.nio.file.StandardWatchEventKinds._
import scala.jdk.CollectionConverters._

object Watcher {

  private[this] val log = Logger.get(getClass.getName)

  sealed trait File

  object File {
    type Children = Map[String, File]

    /** A directory of files */
    case class Dir(children: InstrumentedActivity[Children], state: WatchState) extends File

    /** A regular file */
    sealed trait Reg extends File { def data: Activity[Buf] }
    object Reg {
      def unapply(f: File.Reg): Option[Activity[Buf]] = Some(f.data)
    }

    type UpBufState = Var[Activity.State[Buf]] with Updatable[Activity.State[Buf]]

    case class UpReg(buf: UpBufState = Var(Activity.Pending)) extends Reg {
      def data = Activity(buf)
    }
  }

  private[this] def pool(go: => Unit): Unit = {
    val _ = FuturePool.unboundedPool(go)
  }

  val MaxFileSize = 500.kilobytes

  case class FileTooBig(path: NioPath, size: StorageUnit)
    extends RuntimeException(s"file $path is $size")

  private def read(f: NioPath): Activity.State[Buf] =
    Try {
      val sz = Files.size(f).bytes
      if (sz > MaxFileSize) throw FileTooBig(f, sz)
      Files.readAllBytes(f)
    } match {
      case Return(bytes) => Activity.Ok(Buf.ByteArray.Owned(bytes))
      case Throw(e) => Activity.Failed(e)
    }

  def apply(root: NioPath): File.Dir = {
    require(Files.isDirectory(root))

    val eventState = new WatchState()

    val children = InstrumentedActivity[File.Children] { state =>
      log.debug("fs observing %s", root)
      @volatile var closed = false

      val watcher = root.getFileSystem.newWatchService()

      /*
       * Watch this root directory for updates. When new directories
       * are observed, watch them.
       */
      def watch(dirs: Map[String, File.Dir], regs: Map[String, File.UpReg]): Unit = pool {
        if (closed) return
        log.debug("fs waiting for events on %s", root)
        val key = watcher.take()
        if (closed) return

        var (updirs, upregs) = (dirs, regs)
        for (ev <- key.pollEvents.asScala) {
          eventState.recordEvent(ev)
          ev.context match {
            case p: NioPath =>
              val child = root.resolve(p)
              val name = p.toString
              log.debug("fs event %s on %s", ev.kind, name)
              ev.kind match {
                case ENTRY_CREATE =>
                  if (Files.isDirectory(child)) {
                    log.debug("fs create dir: %s", name)
                    updirs += name -> Watcher(child)
                  } else {
                    log.debug("fs create file: %s", name)
                    val reg = File.UpReg()
                    upregs += name -> reg
                    pool {
                      reg.buf() = read(child)
                    }
                  }

                case ENTRY_MODIFY =>
                  if (Files.isRegularFile(child) && Files.isReadable(child)) {
                    log.debug("fs modify file: %s", name)
                    val reg = upregs(name)
                    pool {
                      reg.buf() = read(child)
                    }
                  }

                case ENTRY_DELETE =>
                  log.debug("fs delete file: %s", name)
                  updirs -= name
                  upregs -= name
              }

            case _ =>
          }
        }

        if (updirs.size != dirs.size || upregs.size != regs.size) {
          log.debug("fs updating dir %s", root)
          state() = Activity.Ok(updirs ++ upregs)
        }

        if (key.reset())
          watch(updirs, upregs)
      }

      val watchKey = Try {
        root.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
      }

      watchKey match {
        case Throw(e) =>
          state() = Activity.Failed(e)
          watcher.close()
        case Return(_) =>
          // Asynchronously load up the initial state of the root and then--if that succeeds--watch it for updates
          pool {
            Try(Files.newDirectoryStream(root)) match {
              case Return(files) =>
                var (dirs, regs) = (Map.empty[String, File.Dir], Map.empty[String, File.UpReg])
                files.asScala.foreach {
                  case d if Files.isDirectory(d) =>
                    val name = d.getFileName.toString
                    log.debug("fs init dir %s", name)
                    dirs += name -> Watcher(d)

                  case f if Files.isRegularFile(f) =>
                    val name = f.getFileName.toString
                    val reg = File.UpReg()
                    //val child = root.resolve(f)
                    val child = f
                    log.debug("fs init file %s => %s", root, child)
                    pool {
                      reg.buf() = read(child)
                    }
                    regs += name -> reg

                  case _ =>
                }

                state() = Activity.Ok(regs ++ dirs)
                watch(dirs, regs)

              case Throw(e) =>
                state() = Activity.Failed(e)
            }
          }
      }

      Closable.make { _ =>
        closed = true
        watcher.close()
        Future.Unit
      }
    }

    File.Dir(children, eventState)
  }
}
