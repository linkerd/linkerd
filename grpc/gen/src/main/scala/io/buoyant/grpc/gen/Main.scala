package io.buoyant.grpc.gen

import com.twitter.app.App
import com.twitter.util.{Await, NonFatal}

object Main extends App {

  def main(): Unit = {
    val req = Generator.parseRequest(System.in)
    val rsp = Generator.gen(req)
    rsp.writeTo(System.out)
  }
}
