package io.buoyant.router.context

import com.twitter.finagle.buoyant.Dst

object DstPathCtx extends LocalKey[Dst.Path]("Dst.Path")

object DstBoundCtx extends LocalKey[Dst.Bound]("Dst.Bound")
