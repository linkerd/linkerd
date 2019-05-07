package io.buoyant.namerd.iface

import io.buoyant.test.FunSuite
import java.nio.ByteBuffer

class ByteBufferSerializersTest extends FunSuite {
  val emptyBuf = ByteBuffer.wrap(new Array[Byte](0))
  test("Return empty string on empty buffer for stamp"){
    assert(ByteBufferSerializers.stamp(emptyBuf) == "")
  }

  test("Return '/' path on empty buffer for path"){
    assert(ByteBufferSerializers.path(Seq(emptyBuf)) == "/")
  }

  test("Return empty string on empty buffer for ipv4"){
    assert(ByteBufferSerializers.ipv4(emptyBuf) == "")
  }
}

