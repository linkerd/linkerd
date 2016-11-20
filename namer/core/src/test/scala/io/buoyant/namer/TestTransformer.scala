package io.buoyant.namer

import com.twitter.finagle.Name.Bound
import com.twitter.finagle.NameTree
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
  override def mk() = new TestTransformer
}
