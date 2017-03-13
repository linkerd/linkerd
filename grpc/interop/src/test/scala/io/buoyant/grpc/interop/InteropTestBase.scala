package io.buoyant.grpc.interop

import com.twitter.util.{Future, Return, Throw, Try}
import grpc.{testing => pb}
import io.buoyant.test.FunSuite

trait InteropTestBase { _: FunSuite =>

  def withClient(f: Client => Future[Unit]): Future[Unit]

  def todo: Map[String, String] = Map(
    "timeout_on_sleeping_server" -> "can't deadline streams yet..."
  )

  def only: Set[String] = Set.empty

  case class Case(name: String, run: Client => Future[Unit])
  def cases: Seq[Case] = Seq(
    Case("empty_unary", _.emptyUnary()),
    Case("large_unary", _.largeUnary(Client.DefaultLargeReqSize, Client.DefaultLargeRspSize)),
    Case("client_streaming", _.clientStreaming(Client.DefaultReqSizes)),
    Case("server_streaming", _.serverStreaming(Client.DefaultRspSizes)),
    Case("ping_pong", _.pingPong(Client.DefaultReqSizes.zip(Client.DefaultRspSizes))),
    Case("empty_stream", _.emptyStream()),
    Case("cancel_after_begin", _.cancelAfterBegin()),
    Case("cancel_after_first_response", _.cancelAfterFirstResponse()),
    Case("status_code_and_message", _.statusCodeAndMessage()),
    Case("timeout_on_sleeping_server", _.timeoutOnSleepingServer())
  )

  for (Case(name, run) <- cases)
    if (only.nonEmpty && !only(name)) ignore(name) {}
    else todo.get(name) match {
      case Some(msg) =>
        test(name) {
          assertThrows[Throwable](await(withClient(run)))
          cancel(s"TODO: $msg")
        }

      case None =>
        test(name) { await(withClient(run)) }
    }

}

