# linkerd #

linkerd is an open-source dynamic linker for microservices. In an
operating system, a dynamic linker handles everything an executable
needs to make library calls. Similarly, for microservice applications,
linkerd handles everything needed to make RPC calls.

linkerd is designed not just to make multi-service applications fast
and reliable (even under extreme traffic) but to decouple application
code from the mechanics of how it’s operationalized.

## Projects ##

### linkerd ###

The _linkerd_ project provides a configuration engine and runtime
harness for routers. `linkerd/core` provides basic public APIs for
configuring and initializing a linker. `linkerd/main` provides a
runtime harness that reads linker configuration from a file.

There are a number of protocol-specific modules that may be
auto-loaded by `linkerd/main`.

### router ###

The _router_ project provides a routing library on top of
[Finagle][0].  The `Router` and `StackRouter` types are much like
Finagle's `Client` and `StackClient` types, except that routers are
not initialized against a specific destination.  Instead, a
destination is identified for each request, and this destination may
be refined through finagle's `Namer` API in order to dispatch the
request to a downstream service.

### auxiliary ### 

#### k8s ####

The _k8s_ project implements a client for a subset of the
[Kubernetes][1] master API.  This package provides the
`io.buoyant.k8s.endpoints` namer that may be used with finagle clients
or routers, independently of _linkerd_.

#### test-util ####

Provides some helpers for writing tests against Finagle's asynchronous
APIs.


## Building ##

[sbt](http://www.scala-sbt.org/) is currently used to build linkerd.
Developers should not use a system-installed version of sbt, and
should instead use the `./sbt` script, which ensures that a compatible
version of sbt is available.

`./sbt compile` builds all components.  `./sbt "project linkerd"
compile` compiles only the _linkerd_ projects.

`./sbt test` runs all unit tests.

`./sbt e2e:test` runs end-to-end tests.  These tests may bind
servers on ephemeral ports, write temporary files to disk, etc.


## License ##

Copyright 2016, Buoyant Inc. All rights reserved.

[0]: https://twitter.github.io/finagle/
[1]: https://k8s.io/
