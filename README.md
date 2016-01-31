![linkerd][l5d-logo]

[![Circle CI][l5d-ci-status]][l5d-ci]
[![Slack Status](https://slack.linkerd.io/badge.svg)](https://slack.linkerd.io)

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

The sbt project consists of many sub-projects:

```
> projects
[info] In file:.../linkerd/
[info] 	 * all
[info] 	   consul
[info] 	   examples
[info] 	   k8s
[info] 	   linkerd
[info] 	   linkerd-admin
[info] 	   linkerd-core
[info] 	   linkerd-main
[info] 	   linkerd-namer
[info] 	   linkerd-namer-consul
[info] 	   linkerd-namer-fs
[info] 	   linkerd-namer-k8s
[info] 	   linkerd-namer-serversets
[info] 	   linkerd-protocol
[info] 	   linkerd-protocol-http
[info] 	   linkerd-protocol-mux
[info] 	   linkerd-protocol-thrift
[info] 	   router
[info] 	   router-core
[info] 	   router-http
[info] 	   router-mux
[info] 	   router-thrift
[info] 	   router-thrift-idl
[info] 	   test-util
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
[info]   +-examples/http:configFile = examples/http.l5d
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

Running the _package_ command produces jar artifacts for all projects:

```
> package
```

Furthermore, the _assembly_ plugin can be used to produce a so-called
"fat jar", containing all library dependencies.  The `linkerd` project
has several build configurations to support packaging:

```
> linkerd/assembly
[info] SHA-1: 5599e65540ebe6122da114be4a8b9a763475b789
[info] Packaging ...linkerd/target/scala-2.11/linkerd-0.0.8-SNAPSHOT.jar ...
[info] Done packaging.
[success] Total time: 14 s, completed Jan 29, 2016 4:29:40 PM
```
```
> linkerd/minimal:assembly
[info] Packaging .../target/scala-2.11/linkerd-minimal-0.0.8-SNAPSHOT.jar ...
[info] Done packaging.
[success] Total time: 13 s, completed Jan 29, 2016 4:30:58 PM
```

The '_minimal_' sbt configuration, supporting only the `http`
protocol and the `io.l5d.fs` namer, is useful for running linkerd
during development.

### Running ###

The `linkerd` project's packaging configurations may also be used to
run linkerd locally, e.g.:

```
> linkerd/minimal:run path/to/config.yml
```

#### Example configurations ####

Furthermore, the `examples` project contains example configurations
that may be quickly started:

```
> examples/http:run
```

As additional configuration files are added into `examples/*.l5d`,
these configurations will be discovered by sbt and added as
configurations.

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
