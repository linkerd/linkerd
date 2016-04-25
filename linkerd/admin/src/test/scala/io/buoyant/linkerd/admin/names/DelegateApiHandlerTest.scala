package io.buoyant.linkerd.admin.names

import com.twitter.finagle.http._
import com.twitter.finagle.{Status => _, _}
import io.buoyant.linkerd._
import io.buoyant.namer._
import io.buoyant.test.Awaits
import java.net.URLEncoder
import org.scalatest.FunSuite

class DelegateApiHandlerTest extends FunSuite with Awaits {

  val yaml =
    """|namers:
       |- kind: test
       |- kind: error
       |
       |routers:
       |- protocol: plain
       |  servers:
       |  - port: 1
       |""".stripMargin
  val namers = Seq(TestNamerInitializer, ErrorNamerInitializer)
  val linker = Linker.Initializers(Seq(TestProtocol.Plain), namers).load(yaml)

  val dtab = Dtab.read("""
    /beh => /error ;
    /bah/humbug => /$/inet/127.1/8080 ;
    /foo => /bah | /beh | /$/fail ;
    /foo => /bar ;
    /boo => /foo ;
    /meh => /heh ;
  """)

  test("/delegate response") {
    val web = new DelegateApiHandler(linker.namers)
    val req = Request()
    req.uri = s"/delegate?path=/boo/humbug&dtab=${URLEncoder.encode(dtab.show, "UTF-8")}"
    val rsp = await(web(req))
    assert(rsp.status == Status.Ok)
    assert(rsp.contentString == """{
      |"type":"delegate","path":"/boo/humbug","dentry":null,"delegate":{
        |"type":"alt","path":"/foo/humbug","dentry":{"prefix":"/boo","dst":"/foo"},"alt":[
          |{"type":"neg","path":"/bar/humbug","dentry":{"prefix":"/foo","dst":"/bar"}},
          |{"type":"delegate","path":"/bah/humbug",
          |"dentry":{"prefix":"/foo","dst":"/bah | /beh | /$/fail"},"delegate":{
            |"type":"leaf","path":"/$/inet/127.1/8080","dentry":{"prefix":"/bah/humbug",
              |"dst":"/$/inet/127.1/8080"},
            |"bound":{"addr":{"type":"bound","addrs":[{"ip":"127.0.0.1","port":8080}],"meta":{}},
              |"id":"/$/inet/127.1/8080","path":"/"}}},
          |{"type":"delegate","path":"/beh/humbug",
            |"dentry":{"prefix":"/foo","dst":"/bah | /beh | /$/fail"},"delegate":{
              |"type":"exception","path":"/error/humbug","dentry":{"prefix":"/beh","dst":"/error"},
                |"message":"error naming /error/humbug"}},
          |{"type":"fail","path":"/$/fail/humbug",
            |"dentry":{"prefix":"/foo","dst":"/bah | /beh | /$/fail"}}]}}
      |""".stripMargin.replaceAllLiterally("\n", ""))
  }

  test("invalid path results in 400") {
    val web = new DelegateApiHandler(linker.namers)
    val req = Request()
    req.uri = s"/delegate?path=invalid-param&dtab=${URLEncoder.encode(dtab.show, "UTF-8")}"
    val rsp = await(web(req))
    assert(rsp.status == Status.BadRequest)
  }

  test("invalid dtab results in 400") {
    val web = new DelegateApiHandler(linker.namers)
    val req = Request()
    req.uri = s"/delegate?path=/boo/humbug&dtab=invalid-param"
    val rsp = await(web(req))
    assert(rsp.status == Status.BadRequest)
  }
}
