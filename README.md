![linkerd][l5d-logo]

[![GitHub license][license-badge]](LICENSE)
[![Circle CI][l5d-ci-badge]][l5d-ci]
[![Slack Status][slack-badge]][slack]
[![Docker Pulls][docker-badge]][docker]

:balloon: Welcome to linkerd! :wave:

linkerd is a transparent *service mesh*, designed to make modern applications
safe and sane by transparently adding service discovery, load balancing, failure
handling, instrumentation, and routing to all inter-service communication.

linkerd (pronouned "linker-DEE") acts as a transparent HTTP/gRPC/thrift/etc
proxy, and can usually be dropped into existing applications with a minimum of
configuration, regardless of what language they're written in. It works with
many common protocols and service discovery backends, including scheduled
environments like Mesos and Kubernetes.

linkerd is built on top of [Netty][netty] and [Finagle][finagle], a
production-tested RPC framework used by high-traffic companies like Twitter,
Pinterest, Tumblr, PagerDuty, and others.

linkerd is hosted by the Cloud Native Computing Foundation ([CNCF][cncf]).

## Want to try it? ##

We distribute binaries which you can download from the [linkerd releases
page](releases). We also publish docker images for each release, which you can
find on [docker hub][docker].

For instructions on how to configure and run linkerd, see the [user
documentation on linkerd.io](https://linkerd.io).

## Working in this repo ##

[BUILD.md](BUILD.md) includes general information on how to work in this repo.
Additionally, there are documents on how to build several of the application
subprojects:

* [linkerd](linkerd/README.md) -- produces `linkerd` router artifacts
* [namerd](namerd/README.md) -- produces `namerd` service discovery artifacts
* [grpc](grpc/README.md) -- produces the `protoc-gen-io.buoyant.grpc` code generator

We :heart: pull requests! See [CONTRIBUTING.md](CONTRIBUTING.md) for info on
contributing changes.

## Related Repos ##

* [linkerd-examples][l5d-eg]: A variety of configuration examples and explanations
* [linkerd-tcp][l5d-tcp]: A lightweight TCP/TLS load balancer that uses namerd
* [linkerd-viz][l5d-viz]: Zero-configuration service dashboard for linkerd
* [linkerd-zipkin][l5d-zipkin]: [Zipkin][zipkin] tracing plugins
* [namerctl][namerctl]: A commandline utility for controlling namerd

## Code of Conduct ##

This project is for everyone. We ask that our users and contributors take a few
minutes to review our [code of conduct][coc].

## License ##

Copyright 2016-2017, Buoyant Inc. All rights reserved.

Licensed under the Apache License, Version 2.0 (the "License"); you may not use
these files except in compliance with the License. You may obtain a copy of the
License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed
under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
CONDITIONS OF ANY KIND, either express or implied. See the License for the
specific language governing permissions and limitations under the License.

<!-- references -->
[cncf]: https://www.cncf.io/about
[coc]: https://github.com/linkerd/linkerd/wiki/Linkerd-code-of-conduct
[docker-badge]: https://img.shields.io/docker/pulls/buoyantio/linkerd.svg
[docker]: https://hub.docker.com/r/buoyantio/linkerd/
[finagle]: https://twitter.github.io/finagle/
[k8s]: https://k8s.io/
[l5d-ci-badge]: https://circleci.com/gh/linkerd/linkerd/tree/master.svg?style=shield&circle-token=06d80fc52dbaeaac316d09b7ad4ada6f7d2bf31f
[l5d-ci]: https://circleci.com/gh/linkerd/linkerd
[l5d-eg]: https://github.com/linkerd/linkerd-examples
[l5d-logo]: https://cloud.githubusercontent.com/assets/9226/12433413/c6fff880-beb5-11e5-94d1-1afb1258f464.png
[l5d-tcp]: https://github.com/linkerd/linkerd-tcp
[l5d-viz]: https://github.com/linkerd/linkerd-viz
[l5d-zipkin]: https://github.com/linkerd/linkerd-zipkin
[license-badge]: https://img.shields.io/github/license/linkerd/linkerd.svg
[namerctl]: https://github.com/linkerd/namerctl
[netty]: https://netty.io/
[slack-badge]: http://slack.linkerd.io/badge.svg
[slack]: http://slack.linkerd.io
[zipkin]: https://github.com/openzipkin/zipkin
