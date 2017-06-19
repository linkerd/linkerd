package io.buoyant.namer

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.Name.Bound
import com.twitter.finagle.{NameTree, Path, Stack}
import com.twitter.util.Activity

class TestTransformer extends NameTreeTransformer {
  override protected def transform(tree: NameTree[Bound]): Activity[NameTree[Bound]] =
    Activity.value(NameTree.Empty)
}

object TestTransformerInitializer extends TransformerInitializer {
  val configClass = classOf[TestTransformerConfig]
  override val configId = "io.l5d.empty"
}

class TestTransformerConfig extends TransformerConfig {

  @JsonIgnore
  val defaultPrefix = Path.read("/io.l5d.empty")

  @JsonIgnore
  override def mk(params: Stack.Params): NameTreeTransformer = new TestTransformer
}
