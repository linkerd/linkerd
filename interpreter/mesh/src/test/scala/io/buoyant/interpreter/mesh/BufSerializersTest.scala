package io.buoyant.interpreter.mesh

import com.twitter.io.Buf
import io.buoyant.test.FunSuite

class BufSerializersTest extends FunSuite {

  test("Converts string ip byte array to string representation"){
    val ipTable = Seq(
      (Buf.ByteArray(Seq(10, 120, 1, 137).map(_.toByte):_*), "10.120.1.137"),
      (Buf.ByteArray(Seq(127, 0, 0, 1).map(_.toByte):_*), "127.0.0.1"),
      (Buf.ByteArray(Seq(255, 255, 255, 255).map(_.toByte):_*),"255.255.255.255"),
      (Buf.ByteArray(Seq(1, 2, 3, 4).map(_.toByte):_*), "1.2.3.4"),
      (Buf.ByteArray(Seq(192, 168, 203, 255).map(_.toByte):_*), "192.168.203.255"),
      (Buf.ByteArray(Seq(0, 0, 0, 0).map(_.toByte):_*), "0.0.0.0")
    )

    for(ip <- ipTable){
      assert(BufSerializers.ipv4(ip._1) == ip._2)
    }
  }


}
