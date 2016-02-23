package io.buoyant.linkerd.admin.names

import com.twitter.finagle.http._
import com.twitter.finagle.{Status => _, _}
import io.buoyant.linkerd._
import io.buoyant.test.Awaits
import java.net.URLEncoder
import org.scalatest.FunSuite

class WebDelegatorTest extends FunSuite with Awaits {

  val linker = Linker.load("""
namers:
- kind: io.buoyant.linkerd.TestNamer

routers:
- protocol: plain
  servers:
  - port: 1
- protocol: fancy
  servers:
  - port: 2
""", Seq(TestProtocol.Plain, TestProtocol.Fancy, TestNamerInitializer))

  val dtab = Dtab.read("""
    /bah/humbug => /$/inet/127.1/8080 ;
    /foo => /bah | /$/fail ;
    /foo => /bar ;
    /boo => /foo ;
    /meh => /heh ;
  """)

  test("/delegate response") {
    val web = new WebDelegator(linker)
    val req = Request()
    req.uri = s"/delegate?n=/boo/humbug&d=${URLEncoder.encode(dtab.show, "UTF-8")}"
    val rsp = await(web(req))
    assert(rsp.status == Status.Ok)
    assert(rsp.contentString ==
      """{"type":"delegate","path":"/boo/humbug","dentry":null,"delegate":{""" +
      """"type":"alt","path":"/foo/humbug","dentry":{"prefix":"/boo","dst":"/foo"},"alt":[""" +
      """{"type":"neg","path":"/bar/humbug","dentry":{"prefix":"/foo","dst":"/bar"}},""" +
      """{"type":"delegate","path":"/bah/humbug","dentry":{"prefix":"/foo","dst":"/bah | /$/fail"},"delegate":{""" +
      """"type":"leaf","path":"/$/inet/127.1/8080","dentry":{"prefix":"/bah/humbug","dst":"/$/inet/127.1/8080"},"bound":{""" +
      """"addr":{"type":"bound","addrs":[{"ip":"127.0.0.1","port":8080}],"meta":{}},""" +
      """"id":"/$/inet/127.1/8080","path":"/"}}},""" +
      """{"type":"fail","path":"/$/fail/humbug","dentry":{"prefix":"/foo","dst":"/bah | /$/fail"}}]}}""")
  }

  test("invalid path results in 400") {
    val web = new WebDelegator(linker)
    val req = Request()
    req.uri = s"/delegate?n=invalid-param&d=${URLEncoder.encode(dtab.show, "UTF-8")}"
    val rsp = await(web(req))
    assert(rsp.status == Status.BadRequest)
  }

  test("invalid dtab results in 400") {
    val web = new WebDelegator(linker)
    val req = Request()
    req.uri = s"/delegate?n=/boo/humbug&d=invalid-param"
    val rsp = await(web(req))
    assert(rsp.status == Status.BadRequest)
  }
}
