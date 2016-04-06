package io.buoyant.namerd.iface

import com.twitter.io.{Buf, Writer}
import com.twitter.util.{Var, Future, Closable}

class StreamingResponse(writer: Writer) {
  var closable: Closable = null
  def close(): Unit = { closable.close() }
  def start[T](data: Var[T])(toBuf: T => Buf): Unit = {
    println("Starting streaming response " + this)
    StreamingResponse.register(this)
    closable = data.changes.respond { t =>
      println(s"Streaming response $this got $t")
      writer.write(toBuf(t)).onFailure { e =>
        println(s"closing streaming response $this due to $e")
        close()
        StreamingResponse.unregister(this)
      }
    }
  }
}

object StreamingResponse {
  private[this] val AllMu = new {}
  private[this] var All: Set[StreamingResponse] = Set.empty

  def register(sr: StreamingResponse): Unit = AllMu.synchronized {
    All += sr
  }

  def unregister(sr: StreamingResponse): Unit = AllMu.synchronized {
    All -= sr
  }
}
