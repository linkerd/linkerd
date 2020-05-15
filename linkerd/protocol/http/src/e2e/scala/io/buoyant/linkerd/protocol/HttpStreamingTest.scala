package io.buoyant.linkerd.protocol

import com.twitter.conversions.StorageUnitOps._
import com.twitter.finagle.buoyant._
import com.twitter.finagle.http.param.{MaxRequestSize, Streaming}
import com.twitter.finagle.http.{Method, Request, Response, Version}
import com.twitter.finagle.{Http, Service, Stack}
import com.twitter.io.{Buf, BufReader, BufReaders, Reader}
import com.twitter.util.{Future, Promise, StorageUnit}
import io.buoyant.test.{Awaits, BudgetedRetries}
import java.net.InetSocketAddress

import org.scalatest.{FunSuite, MustMatchers, OptionValues}

/**
 *
 * This test is to illustrate the behavior of `fixedLengthStreamedAfter` parameter. The following
 * cases are covered:
 *
 * chunked message - a message with `Transfer-Encoding: chunked`. These are never aggregated and are
 *                   always streamed
 *
 * non-chunked message - a message that has a known fixed size set via the "Content-Length" header.
 *                       There are two options here. If the size of the message is less than the
 *                       value set for streamAfterContentLengthKB, then this message will be
 *                       buffered. If the size is more, then the message will be streamed.
 *
 * non-chunked streaming message - a message that is not chunk-encoded and does not have
 *                                 "Content-Length" would fall under this category. If that is the
 *                                 case, the message will be streamed.
 *
 *
 * NB: A very important detail is that a non chunked message(i.e. "Content-Length" is set) that
 * is larger than maxRequestKb cannot be processed. (even when the "Content-Length" value is
 * larger than fixedLengthStreamedAfter)
 *
 */



class HttpStreamingTest extends FunSuite
  with Awaits
  with MustMatchers
  with OptionValues
  with BudgetedRetries {


  def withServerAndClient(
    fixedLengthStreamedAfter: Option[StorageUnit],
    messageBody: String,
    maxRequestSize: Option[StorageUnit] = None,
    withContentLength: Boolean = false,
    withChunkedEncoding: Boolean = false
  )(f: Request => Any) = {

    val Address = "127.0.0.1:8080"
    val params = Stack.Params.empty.maybeWith(fixedLengthStreamedAfter.map(v => Streaming(v)))
      .maybeWith(maxRequestSize.map(MaxRequestSize(_)))

    val receivedFut: Promise[Request] = Promise()
    val svc = Service.mk[Request, Response] { req =>
      receivedFut.setValue(req)
      Future.value(Response(req))
    }

    val server = Http.server.withParams(params).serve(Address, svc)
    val addr = server.boundAddress.asInstanceOf[InetSocketAddress]
    val client = Http.client.newService(s"${addr.getHostName}:${addr.getPort}", "client")


    val req = Request(
      Version.Http11,
      Method.Get,
      Address,
      Reader.fromBuf(Buf.Utf8(messageBody), 1)
    )


    if (withContentLength) {
      req.headerMap.put("Content-Length", messageBody.length.toString)
    }
    if (withChunkedEncoding) {
      req.headerMap.put("Transfer-Encoding", "chunked")
    }

    client(req)
    f(await(receivedFut))
    await(server.close())
    await(client.close())

  }


  test("server will stream fixed size messages exceeding streamAfterContentLengthKB") {
    val messageBody = "must-stream"
    withServerAndClient(Some((messageBody.length - 1).bytes), messageBody, withContentLength = true)
    { receivedRequest =>
      assert(receivedRequest.isChunked)
      val receivedBody = await(BufReader.readAll(receivedRequest.reader))
      assert(receivedBody == Buf.Utf8(messageBody))
      assert(receivedRequest.contentString.isEmpty)
    }

  }

  test("server will buffer fixed size messages less than streamAfterContentLengthKB in size") {
    val messageBody = "must-not-stream"
    withServerAndClient(Some((messageBody.length + 1).bytes), messageBody, withContentLength = true)
    { receivedRequest =>
      assert(!receivedRequest.isChunked)
      assert(receivedRequest.contentString == messageBody)
    }

  }



  test(
    "server will stream messages missing Content-Length (even if below streamAfterContentLengthKB in size)"
  ) {
    val messageBody = "must-stream"
    withServerAndClient(Some(1.gigabyte), messageBody) { receivedRequest =>
      val receivedBody = await(BufReader.readAll(receivedRequest.reader))
      assert(receivedBody == Buf.Utf8(messageBody))
      assert(receivedRequest.isChunked)
      assert(receivedRequest.contentString.isEmpty)
    }
  }


  test("server will always stream messages that have Content-Length and Transfer-Encoding:chunked")
  {
    val messageBody = "must-stream"
    withServerAndClient(
      Some(0.bytes),
      messageBody,
      withChunkedEncoding = true,
      withContentLength = true
    ) { receivedRequest =>
      val receivedBody = await(BufReader.readAll(receivedRequest.reader))
      assert(receivedBody == Buf.Utf8(messageBody))
      assert(receivedRequest.isChunked)
      assert(receivedRequest.contentString.isEmpty)
    }
  }

}
