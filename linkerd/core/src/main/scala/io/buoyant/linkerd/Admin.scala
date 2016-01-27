package io.buoyant.linkerd

import com.fasterxml.jackson.core.JsonParser
import com.twitter.finagle.Stack

case class Admin(params: Stack.Params = Stack.Params.empty) {

  def read(json: JsonParser): Admin = {
    copy(params = Admin.parser.readObject(json, params))
  }
}

object Admin {
  case class AdminPort(port: Int)
  implicit object AdminPort extends Stack.Param[AdminPort] {
    override def default: AdminPort = AdminPort(9990)
  }

  val port = Parsing.Param.Int("port")(AdminPort(_))

  def parser = Parsing.Params(port)
}
