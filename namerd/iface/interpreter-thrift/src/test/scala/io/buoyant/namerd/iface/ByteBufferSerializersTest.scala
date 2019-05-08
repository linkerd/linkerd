package io.buoyant.namerd.iface

import io.buoyant.test.FunSuite
import java.nio.ByteBuffer

class ByteBufferSerializersTest extends FunSuite {
  val emptyBuf = ByteBuffer.wrap(new Array[Byte](0))
  val emptyBufwithCap = ByteBuffer.wrap(new Array[Byte](8))
  test("Return empty string on empty buffer for stamp"){
    assert(ByteBufferSerializers.stamp(emptyBuf) == "")
  }

  test("Return zero on empty direct buffer with cap 8 for stamp"){
    val directBuf = ByteBuffer.allocateDirect(8)
    assert(ByteBufferSerializers.stamp(directBuf) == "0")
  }

  test("Return empty string on empty direct buffer with cap 0 for stamp"){
    val directBuf = ByteBuffer.allocateDirect(0)
    assert(ByteBufferSerializers.stamp(directBuf) == "")
  }

  test("Return '/' path on empty buffer for path"){
    assert(ByteBufferSerializers.path(Seq(emptyBuf)) == "/")
  }

  test("Return empty string on empty buffer for ipv4"){
    assert(ByteBufferSerializers.ipv4(emptyBuf) == "")
  }

  test("Return 0.0.0.0 on empty direct buffer with cap 4 for ipv4"){
    val directBuf = ByteBuffer.allocateDirect(4)
    assert(ByteBufferSerializers.ipv4(directBuf) == "0.0.0.0")
  }
  test("Return empty string on empty direct buffer with cap 0 for ipv4"){
    val directBuf = ByteBuffer.allocateDirect(0)
    assert(ByteBufferSerializers.ipv4(directBuf) == "")
  }
}

