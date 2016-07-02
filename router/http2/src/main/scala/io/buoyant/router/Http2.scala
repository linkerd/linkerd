package io.buoyant.router

object Http2 {

  //   case class Server(
  //       stack: Stack[ServiceFactory[Request, Response]] = Server.stack,
  //       params: Stack.Params = StackServer.defaultParams + ProtocolLibrary("http"))
  //     extends StdStackServer[Request, Response, Server] {

  //     protected type In = Any
  //     protected type Out = Any

  //     protected def newListener(): Listener[Any, Any] =
  //       params[param.HttpImpl].listener(params)

  //     protected def newDispatcher(
  //       transport: Transport[In, Out],
  //       service: Service[Request, Response]
  //     ) = {
  //       val Stats(stats) = params[Stats]
  //       new HttpServerDispatcher(
  //         transport,
  //         service,
  //         stats.scope("dispatch"))
  //     }

  //     protected def copy1(
  //       stack: Stack[ServiceFactory[Request, Response]] = this.stack,
  //       params: Stack.Params = this.params
  //     ): Server = copy(stack, params)
  //   }
}
