# io.buoyant.grpc #

Protobuf3 & gRPC code generation for Finagle using
io.buoyant:finagle-h2.

**Status**: Experimental; the runtime API may change.

## io.buoyant:grpc-gen ##

The grpc-gen package assembles the `protoc-gen-io.buoyant.grpc` protoc
plugin. This generator creates code that encodes/decodes idiomatic
Scala case classes for proto3 data structures and generates gRPC
server bindings using io.buoyant:finagle-h2.

A binary may be produced with the following command:

```
sbt> grpc-gen/assembly
...
[info] Packaging /.../l5d/grpc/gen/target/scala-2.11/protoc-gen-io.buoyant.grpc ...
```

## SBT settings ##

The `Grpc.grpcGenSettings` SBT settings may be mixed into a project to
automatically generate scala code from protobuf definitions. These
settings ensure that the protoc-gen-io.buoyant.grpc binary is
available so that the binary need not be manually installed.

## io.buoyant:grpc-runtime ##

The grpc-runtime package provides de/serialization and communication
primitives used by generated code.

## Example: grpc-eg ##

The grpc-eg project contains only the proto definitions for an example
gRPC service, along with a test that exercises this service.
