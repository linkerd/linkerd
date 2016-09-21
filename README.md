![linkerd][l5d-logo]

[![GitHub license](https://img.shields.io/github/license/buoyantio/linkerd.svg)](LICENSE)
[![Circle CI][l5d-ci-status]][l5d-ci]
[![Coverage Status](https://coveralls.io/repos/github/BuoyantIO/linkerd/badge.svg)](https://coveralls.io/github/BuoyantIO/linkerd)
[![Slack Status](http://slack.linkerd.io/badge.svg)](http://slack.linkerd.io)

:balloon: Welcome to linkerd! :wave:

linkerd is an out-of-process network stack for microservices. It
functions as a transparent RPC proxy, handling everything needed to
make inter-service RPC safe and sane--including load-balancing,
service discovery, instrumentation, and routing.

linkerd is designed to drop into existing applications with a minimum
of configuration, and works with many common RPC formats and service
discovery backends.

linkerd is built on top of [Finagle][finagle], a production-tested RPC
framework used by high-traffic companies like Twitter, Pinterest,
Tumblr, PagerDuty, and others.

For more information, please see [linkerd.io](https://linkerd.io).

This repo contains two main projects: linkerd itself and namerd, a service for
centrally managing routing policy and fronting service discovery.

* [linkerd](linkerd/README.md)
* [namerd](namerd/README.md)

## Quickstart ##

### Boot linkerd ###

```
$ ./sbt linkerd-examples/http:run # build and run linkerd
$ open http://localhost:9990      # open linkerd's admin interface in a browser
```

More details at [linkerd's quickstart](linkerd/README.md#quickstart)


### Boot namerd ###

```
$ ./sbt namerd-examples/basic:run # build and run namerd
$ open http://localhost:9991      # open namerd's admin interface in a browser
$ curl :4180/api/1/dtabs          # test namerd's http interface
```

More details at [namerd's quickstart](namerd/README.md#quickstart)

## Working in this repository ##

[sbt][sbt] is used to build and test linkerd. Developers should not
use a system-installed version of sbt, and should instead use the
`./sbt` script, which ensures that a compatible version of sbt is
available.

`./sbt` accepts commands on the command line, or if it is invoked with
no arguments it loads an interactive sbt shell:

```
$ ./sbt
>
```

The sbt project consists of many sub-projects. To list all projects run:

```
> projects
```

These projects are configured in
[`project/LinkerdBuild.scala`](project/LinkerdBuild.scala), which may
be edited to include additional sub-projects, build configurations,
etc. [`project/Base.scala`](project/Base.scala) is used to augment
sbt's api.

You may run commands, for instance, _compile_ on the aggregate
project, _all_, by invoking:

```
> compile
```

Commands may be scoped by project, as in:

```
> router-http/test
```

Or by configuration as in:

```
> e2e:test
```

or

```
> router-http/e2e:test
```

The _inspect_ command helps describe how a command is configured:

```
> inspect tree examples/http:run
[info] examples/http:run = InputTask[Unit]
[info]   +-examples/http:configFile = examples/http.yaml
[info]   | +-examples/http:configuration = http
[info]   |
[info]   +-examples/http:runtimeConfiguration = minimal
[info]   +-*/*:settingsData = Task[sbt.Settings[sbt.Scope]]
[info]
```

### Tests ###

There are several supported test configurations:
- `test`: pure unit tests that do not require system or network
- `e2e`: tests that compose multiple modules; may allocate random
ephemeral ports and write temporary files
- `integration`: tests that rely on external services or programs that
require external installation and/or configuration.

Both unit and end-to-end tests are run as part of our
[Continuous Integration][l5d-ci] setup.

Tests may be run with:

```
> test
...
[success] Total time: 14 s, completed Jan 29, 2016 4:24:16 PM
```
```
> e2e:test
...
[success] Total time: 8 s, completed Jan 29, 2016 4:25:18 PM
```

sbt also provides a `testQuick` command which is especially useful
when actively editing code:

```
> ~testQuick
```

The `validator` project provides an integration test that operates on
assembled artifacts. It may be run with:
```
> validator/validateAssembled
```

#### Writing tests ####

Test files for each of the above test configurations are stored in a
per-configuration directory structure, e.g.:

```
$ ls -l router/http/src
e2e
main
test
```

Tests are written using the [ScalaTest][scalatest] testing framework,
and specifically the [`FunSuite`][funsuite] mixin, which supports
xUnit-like semantics. We avoid using mocking frameworks when testing
our own code, as they tend to introduce as many problems as they
solve. Tests may leverage the `test-util` project, which provides some
helpers for writing tests against Finagle's asynchronous APIs.

### Packaging ###

#### Building an executable ####

linkerd provides a plugin system so that features may be chosen at
packaging time.  To this end, there are multiple configurations for
running and packaging linkerd executables.

The _assembly_ plugin can be used to produce an executable containing
all library dependencies.  The `linkerd` subproject has several build
configurations to support packaging:

```
> linkerd/assembly
[info] SHA-1: 5599e65540ebe6122da114be4a8b9a763475b789
[info] Packaging ...linkerd/target/scala-2.11/linkerd-0.0.10-SNAPSHOT-exec ...
[info] Done packaging.
[success] Total time: 14 s, completed Jan 29, 2016 4:29:40 PM
```

##### _minimal_ build configuration #####

The '_minimal_' sbt configuration, supporting only the `http` protocol
and the `io.l5d.fs` namer, is useful for running linkerd during
development. This configuration may be specified explicitly to scope
build commands:

```
> linkerd/minimal:assembly
[info] Packaging ...linkerd/target/scala-2.11/linkerd-minimal-0.0.10-SNAPSHOT-exec ...
[info] Done packaging.
[success] Total time: 13 s, completed Jan 29, 2016 4:30:58 PM
```

Similarly, a namerd executable can be produced with the command:
```
> namerd/assembly
```

#### Releasing ####

Before releasing ensure that [CHANGES.md](CHANGES.md) is updated to include the
version that you're trying to release.

By default, the _-SNAPSHOT_ suffix is appended to the version number when
building linkerd.  In order to build a non-snapshot (i.e. releasable) version of
linkerd, the build must occur from a release tag in git.

For example, in order to build the 0.0.10 release of linkerd:

1. Ensure that the head version is 0.0.10
2. `git tag 0.0.10 && git push origin 0.0.10`
3. `./sbt linkerd/assembly namerd/assembly` will produce executables
  in _linkerd/target/scala-2.11/linkerd-0.0.10-exec_ and
  _namerd/target/scala-2.11/namerd-0.0.10-exec_.

#### Docker ####

Each of these configurations may be used to build a docker image.
```
> ;linkerd/docker ;namerd/docker
...
[info] Tagging image 94ab0793addf with name: buoyantio/linkerd:0.0.10-SNAPSHOT
```

The produced image does not contain any configuration.  It's expected
that configuration is provided by another docker layer or volume.  For
example, if you have linkerd configuration in
_path/to/myapp/linkerd.yml_, you could start linkerd in docker with
the following command:

```
$ docker run -p 4140:4140 -p 9990:9990 -v /absolute/path/to/myapp:/myapp buoyantio/linkerd:0.0.10-SNAPSHOT /myapp/linkerd.yml
```

For local testing convenience, we supply a config that routes to a single backend on _localhost:8080_.

```
$ docker run -p 4140:4140 -p 9990:9990 -v /path/to/linkerd/linkerd/examples:/config buoyantio/linkerd:0.8.1-SNAPSHOT /config/static_namer.yaml
```

The list of image names may be changed with a command like:

```
> set imageNames in docker in (linkerd, Bundle) += ImageName("gcr.io/gce-project/linkerd:v"+version.value)
...
> show linkerd/bundle:docker::imageNames
[info] List(buoyantio/linkerd:0.0.10-SNAPSHOT, gcr.io/gce-project/linkerd:v0.0.10-SNAPSHOT)
```

#### DCOS ####

Namerd supports a DCOS-specific configuration. When used in conjunction with
namerd's `io.l5d.zk` dtab storage, this
configuration provides bootstrapping of the ZooKeeper `pathPrefix`, along with
a default dtab.

##### Run locally #####

This executes only the namerd-dcos-bootstrap command, it does not boot namerd.

```bash
$ ./sbt "namerd/dcos:run-main io.buoyant.namerd.DcosBootstrap namerd/examples/zk.yaml"
```

##### Run assembly script locally #####

The assembly script executes two commands serially:
1. runs namerd-dcos-bootstrap
2. boots namerd

```bash
$ ./sbt namerd/dcos:assembly
$ namerd/target/scala-2.11/namerd-0.8.1-SNAPSHOT-dcos-exec namerd/examples/zk.yaml
```

##### Run assembly script in docker #####

```bash
$ ./sbt namerd/dcos:docker
$ docker run -p 2181:2181 -p 4180:4180 -v /path/to/repo:/myapp -w /myapp buoyantio/namerd:0.8.1-SNAPSHOT-dcos namerd/examples/zk.yaml
```

### Contributing ###

See [CONTRIBUTING.md](CONTRIBUTING.md) for more details about how to
contribute.

### Style ###

We generally follow [Effective Scala][es] and the
[Scala Style Guide][ssg]. When in doubt, we try to use Finagle's
idioms.

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

<!-- references -->
[consul]: https://consul.io/
[es]: https://twitter.github.io/effectivescala/
[finagle]: https://twitter.github.io/finagle/
[funsuite]: http://www.scalatest.org/getting_started_with_fun_suite
[k8s]: https://k8s.io/
[l5d-ci]: https://circleci.com/gh/BuoyantIO/linkerd
[l5d-ci-status]: https://circleci.com/gh/BuoyantIO/linkerd/tree/master.svg?style=shield&circle-token=06d80fc52dbaeaac316d09b7ad4ada6f7d2bf31f
[l5d-logo]: https://cloud.githubusercontent.com/assets/9226/12433413/c6fff880-beb5-11e5-94d1-1afb1258f464.png
[sbt]: http://www.scala-sbt.org/
[scalatest]: http://www.scalatest.org/
[ssg]: http://docs.scala-lang.org/style/scaladoc.html
