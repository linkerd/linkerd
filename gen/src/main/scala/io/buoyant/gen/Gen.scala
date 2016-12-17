package io.buoyant.gen

import java.io.File
import scala.language.experimental.macros
import scala.reflect.macros.whitebox.Context
import scala.sys.process._

object Gen {

  def fromIdl(path: String): Object = macro fromIdl_impl

  def fromIdl_impl(c: Context)(path: c.Expr[String]) = {
    import c.universe._

    val pathLiteral = path.tree match {
      case Literal(Constant(s: String)) => s
      case _ => c.abort(c.enclosingPosition, "Idl path must be a string literal")
    }

    val name = new File(pathLiteral).getName
    val code = c.parse(s"amm gen/Idlc.sc $pathLiteral".!!)

    val className = TypeName("Prefix")

    c.Expr[Object](
      q"""class $className {
            $code
          }
          new $className {}
        """
    )
  }
}
