package io.buoyant.namerd.iface

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.SerializerProvider
import io.buoyant.config.ConfigSerializer
import io.buoyant.namerd.iface.{thriftscala => thrift}

class BoundSerializer extends ConfigSerializer[thrift.Bound] {
  import ByteBufferSerializers._

  def serializeNode(node: thrift.BoundNode, gen: JsonGenerator): Unit = {
    gen.writeStartObject()
    node match {
      case alt: thrift.BoundNode.Alt =>
        gen.writeStringField("type", "alt")
        gen.writeFieldName("alt")
        gen.writeArray(alt.alt.toArray, 0, alt.alt.length)
      case empty: thrift.BoundNode.Empty =>
        gen.writeStringField("type", "empty")
      case fail: thrift.BoundNode.Fail =>
        gen.writeStringField("type", "fail")
      case leaf: thrift.BoundNode.Leaf =>
        gen.writeStringField("type", "leaf")
        gen.writeStringField("id", path(leaf.leaf.id))
        gen.writeStringField("residual", path(leaf.leaf.residual))
      case neg: thrift.BoundNode.Neg =>
        gen.writeStringField("type", "neg")
      case weighted: thrift.BoundNode.Weighted =>
        gen.writeStringField("type", "union")
        gen.writeArrayFieldStart("weighted")
        for (node <- weighted.weighted) {
          gen.writeStartObject()
          gen.writeNumberField("weight", node.weight)
          gen.writeNumberField("id", node.id)
          gen.writeEndObject()
        }
        gen.writeEndArray()
      case unknown: thrift.BoundNode.UnknownUnionField =>
        gen.writeStringField("type", "unknown")
    }
    gen.writeEndObject()
  }

  override def serialize(
    value: thrift.Bound,
    gen: JsonGenerator,
    provider: SerializerProvider
  ): Unit = {
    gen.writeStartObject()
    gen.writeStringField("stamp", stamp(value.stamp))
    gen.writeStringField("namespace", value.ns)
    gen.writeObjectFieldStart("tree")
    gen.writeFieldName("root")
    serializeNode(value.tree.root, gen)
    gen.writeObjectFieldStart("nodes")
    for ((id, node) <- value.tree.nodes) {
      gen.writeFieldName(id.toString)
      serializeNode(node, gen)
    }
    gen.writeEndObject()
    gen.writeEndObject()
    gen.writeEndObject()
  }
}
