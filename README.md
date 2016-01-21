![linkerd](https://cloud.githubusercontent.com/assets/9226/12433413/c6fff880-beb5-11e5-94d1-1afb1258f464.png)

[![Circle CI](https://circleci.com/gh/BuoyantIO/linkerd/tree/master.svg?style=shield&circle-token=06d80fc52dbaeaac316d09b7ad4ada6f7d2bf31f)](https://circleci.com/gh/BuoyantIO/linkerd/tree/master)
[![Slack Status](https://slack.linkerd.io/badge.svg)](https://slack.linkerd.io)

linkerd is an open-source dynamic linker for microservices. In an operating
system, a dynamic linker handles everything an executable needs to make library
calls. Similarly, for microservice applications, linkerd handles everything
needed to make RPC calls.

linkerd is designed not just to make multi-service applications fast and
reliable (even under extreme traffic) but to decouple application code from the
mechanics of how it’s operationalized.

## Projects ##

### linkerd ###

The _linkerd_ project provides a configuration engine and runtime harness for
routers. `linkerd/core` provides basic public APIs for configuring and
initializing a linker. `linkerd/main` provides a runtime harness that reads
linker configuration from a file.

There are a number of protocol-specific modules that may be auto-loaded by
`linkerd/main`.

### router ###

The _router_ project provides a routing library on top of [Finagle][finagle].
The `Router` and `StackRouter` types are much like Finagle's `Client` and
`StackClient` types, except that routers are not initialized against a specific
destination. Instead, a destination is identified for each request, and this
destination may be refined through Finagle's `Namer` API in order to dispatch
the request to a downstream service.

### auxiliary ###

#### k8s ####

The _k8s_ project implements a client for a subset of the [Kubernetes][k8s]
master API. This package provides the `io.buoyant.k8s.endpoints` namer that may
be used with finagle clients or routers, independently of _linkerd_.

#### consul ####

The _consul_ project implements a client for a subset of the
[Consul][consul] Catalog API.  It provides the `io.buoyant.consul.catalog` namer.

#### test-util ####

The _test-util_ project provides some helpers for writing tests against
Finagle's asynchronous APIs.

## Contributing ##

See [CONTRIBUTING.md](CONTRIBUTING.md) for more details about how to contribute.

## License ##

Copyright 2016, Buoyant Inc. All rights reserved.

Licensed under the Apache License, Version 2.0 (the "License"); you may not use
these files except in compliance with the License. You may obtain a copy of the
License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed
under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
CONDITIONS OF ANY KIND, either express or implied. See the License for the
specific language governing permissions and limitations under the License.

[finagle]: https://twitter.github.io/finagle/
[k8s]: https://k8s.io/
[consul]: https://consul.io/
