package io.buoyant.linkerd.protocol.h2

import com.twitter.finagle.buoyant.h2.{AccessLogger, Request, Response}
import com.twitter.finagle.{ServiceFactory, Stack, Stackable}
import com.twitter.logging._
import io.buoyant.router.RouterLabel

object H2AccessLogger {

  object param {
    case class File(path: String)
    implicit object File extends Stack.Param[File] {
      val default = File("")
    }

    case class RollPolicy(policy: Policy)
    implicit object RollPolicy extends Stack.Param[RollPolicy] {
      val default = RollPolicy(Policy.Never)
    }

    case class Append(append: Boolean)
    implicit object Append extends Stack.Param[Append] {
      val default = Append(true)
    }

    case class RotateCount(count: Int)
    implicit object RotateCount extends Stack.Param[RotateCount] {
      val default = RotateCount(-1)
    }
  }

  val module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module5[RouterLabel.Param, param.File, param.RollPolicy, param.Append, param.RotateCount, ServiceFactory[Request, Response]] {
      val role = Stack.Role("H2AccessLogger")
      val description = "Log h2 requests/response summaries to a file"
      def make(
        label: RouterLabel.Param,
        file: param.File,
        roll: param.RollPolicy,
        append: param.Append,
        rotate: param.RotateCount,
        factory: ServiceFactory[Request, Response]
      ): ServiceFactory[Request, Response] =
        file match {
          case param.File("") => factory
          case param.File(path) =>
            val logger = LoggerFactory(
              node = "access_" + label,
              level = Some(Level.INFO),
              handlers = List(FileHandler(
                path, roll.policy, append.append, rotate.count,
                // avoid the default prefix
                formatter = new com.twitter.logging.Formatter(prefix = ""),
                level = Some(Level.INFO)
              )),
              useParents = false
            )
            AccessLogger(logger()).andThen(factory)
        }
    }

}
