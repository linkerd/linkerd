![linkerd][l5d-logo]

[![GitHub license][license-badge]](LICENSE)
[![Circle CI][l5d-ci-badge]][l5d-ci]
[![Slack Status][slack-badge]][slack]
[![Docker Pulls][docker-badge]][docker]
[![CII Best Practices][cii-badge]][cii]

** This repo is for the old, 1.x version of Linkerd. Linkerd development is now
happening in the [linkerd2][l5d2] repo.**

Linkerd 1.x (pronounced "linker-DEE") acts as a transparent
HTTP/gRPC/thrift/etc proxy, and can usually be dropped into existing
applications with a minimum of configuration, regardless of what language
they're written in. It works with many common protocols and service discovery
backends, including scheduled environments like Mesos and Kubernetes.

Linkerd is built on top of [Netty][netty] and [Finagle][finagle], a
production-tested RPC framework used by high-traffic companies like Twitter,
Pinterest, Tumblr, PagerDuty, and others.

Linkerd is hosted by the Cloud Native Computing Foundation ([CNCF][cncf]).

## Want to try it? ##

We distribute binaries which you can download from the [Linkerd releases
page][releases]. We also publish Docker images for each release, which you can
find on [Docker Hub][docker].

For instructions on how to configure and run Linkerd, see the [1.x user
documentation on linkerd.io](https://linkerd.io/1/).

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

* [linkerd2][l5d2]: The main repo for Linkerd 2.x and where current development
  is happening.
* [linkerd-examples][l5d-eg]: A variety of configuration examples and explanations
* [linkerd-tcp][l5d-tcp]: A lightweight TCP/TLS load balancer that uses Namerd
* [linkerd-viz][l5d-viz]: Zero-configuration service dashboard for Linkerd
* [linkerd-zipkin][l5d-zipkin]: [Zipkin][zipkin] tracing plugins
* [namerctl][namerctl]: A commandline utility for controlling Namerd

## Code of Conduct ##

This project is for everyone. We ask that our users and contributors take a few
minutes to review our [code of conduct][coc].

## License ##

Copyright 2018, Linkerd Authors. All rights reserved.

Licensed under the Apache License, Version 2.0 (the "License"); you may not use
these files except in compliance with the License. You may obtain a copy of the
License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed
under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
CONDITIONS OF ANY KIND, either express or implied. See the License for the
specific language governing permissions and limitations under the License.

<!-- references -->
[cii-badge]:https://bestpractices.coreinfrastructure.org/projects/1445/badge
[cii]: https://bestpractices.coreinfrastructure.org/projects/1445
[cncf]: https://www.cncf.io/about
[coc]: https://github.com/linkerd/linkerd/wiki/Linkerd-code-of-conduct
[docker-badge]: https://img.shields.io/docker/pulls/buoyantio/linkerd.svg
[docker]: https://hub.docker.com/r/buoyantio/linkerd/
[finagle]: https://twitter.github.io/finagle/
[k8s]: https://k8s.io/
[l5d-ci-badge]: https://circleci.com/gh/linkerd/linkerd/tree/main.svg?style=shield&circle-token=06d80fc52dbaeaac316d09b7ad4ada6f7d2bf31f
[l5d-ci]: https://circleci.com/gh/linkerd/linkerd
[l5d-eg]: https://github.com/linkerd/linkerd-examples
[l5d-logo]: https://user-images.githubusercontent.com/9226/33582867-3e646e02-d90c-11e7-85a2-2e238737e859.png
[l5d-tcp]: https://github.com/linkerd/linkerd-tcp
[l5d-viz]: https://github.com/linkerd/linkerd-viz
[l5d-zipkin]: https://github.com/linkerd/linkerd-zipkin
[l5d2]: https://github.com/linkerd/linkerd2
[license-badge]: https://img.shields.io/github/license/linkerd/linkerd.svg
[namerctl]: https://github.com/linkerd/namerctl
[netty]: https://netty.io/
[slack-badge]: https://slack.linkerd.io/badge.svg
[slack]: https://slack.linkerd.io
[zipkin]: https://github.com/openzipkin/zipkin
[releases]: https://github.com/linkerd/linkerd/releases
