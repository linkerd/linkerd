package io.buoyant.interpreter.mesh

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.SerializerProvider
import io.buoyant.config.ConfigSerializer
import io.linkerd.mesh
import io.linkerd.mesh.BoundNameTree

class BoundNameTreeSerializer extends ConfigSerializer[mesh.BoundNameTree] {
  override def serialize(
    value: BoundNameTree,
    gen: JsonGenerator,
    provider: SerializerProvider
  ): Unit = {
    value.node match {
      case Some(node) => serializeNode(node, gen)
      case None => gen.writeNull()
    }
  }

  private[this] def serializeNode(node: mesh.BoundNameTree.OneofNode, gen: JsonGenerator): Unit = {
    node match {
      case mesh.BoundNameTree.OneofNode.Alt(alt) =>
        gen.writeStartObject()
        gen.writeStringField("type", "alt")
        gen.writeArrayFieldStart("alt")
        for (tree <- alt.trees.flatMap(_.node)) serializeNode(tree, gen)
        gen.writeEndArray()
        gen.writeEndObject()
      case mesh.BoundNameTree.OneofNode.Empty(_) =>
        gen.writeStartObject()
        gen.writeStringField("type", "empty")
        gen.writeEndObject()
      case mesh.BoundNameTree.OneofNode.Fail(_) =>
        gen.writeStartObject()
        gen.writeStringField("type", "fail")
        gen.writeEndObject()
      case mesh.BoundNameTree.OneofNode.Leaf(leaf) =>
        gen.writeStartObject()
        gen.writeStringField("type", "leaf")
        for (id <- leaf.id) gen.writeStringField("id", BufSerializers.path(id.elems))
        for (residual <- leaf.residual) gen.writeStringField("residual", BufSerializers.path(residual.elems))
        gen.writeEndObject()
      case mesh.BoundNameTree.OneofNode.Neg(_) =>
        gen.writeStartObject()
        gen.writeStringField("type", "neg")
        gen.writeEndObject()
      case mesh.BoundNameTree.OneofNode.Union(union) =>
        gen.writeStartObject()
        gen.writeStringField("type", "union")
        gen.writeArrayFieldStart("weighted")
        for (mesh.BoundNameTree.Union.Weighted(weight, tree) <- union.trees) {
          gen.writeStartObject()
          for (w <- weight) gen.writeStringField("weight", w.toString)
          for (t <- tree.flatMap(_.node)) {
            gen.writeFieldName("tree")
            serializeNode(t, gen)
          }
          gen.writeEndObject()
        }
        gen.writeEndArray()
        gen.writeEndObject()
    }
  }
}
