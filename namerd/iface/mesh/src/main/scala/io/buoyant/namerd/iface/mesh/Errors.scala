package io.buoyant.namerd.iface.mesh

import com.twitter.finagle.Path
import io.buoyant.grpc.runtime.GrpcStatus

object Errors {
  val NoRoot = GrpcStatus.InvalidArgument("No namespace specified")
  val RootNotFound = GrpcStatus.NotFound("Root not found")
  val NoName = GrpcStatus.NotFound("No name given")
  def InvalidRoot(p: Path) =
    GrpcStatus.InvalidArgument(s"Invalid root: `${p.show}` must have exactly one segment")
}
