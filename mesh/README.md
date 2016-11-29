# io.linkerd.mesh #

A set of gRPC APIs that can be used to control linkerd.

The mesh consists of client-side plugin interfaces (initially, just
NameInterpreter) as well as server-side service implementations
(initially, in namerd's 'io.l5d.mesh' iface).

**Status**: Experimental; the API will break in an upcoming release.

## TODO ##

- [] needs automated tests
- [] io.l5d.mesh interpreter is definitely not resilient to errors
- [] determine if additional observation caching is necessary

## gRPC services ##

- `io.linkerd.mesh.Codec` -- Supports parsing/formatting for thin clients.
- `io.linkerd.mesh.Delegator` -- Supports name tree diagnostics.
- `io.linkerd.mesh.Interpreter` -- Supports name tree binding.
- `io.linkerd.mesh.Resolver` -- Supports lookup of address sets (essentially, service discovery).

