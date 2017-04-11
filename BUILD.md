## Development guide ##

This document will help you build linkerd from source.

This repo contains two main application build targets:
- linkerd, a service mesh router
- namerd, a service for centrally managing routing policy and fronting service discovery.

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
> inspect tree linkerd-examples/http:run
[info] linkerd-examples/http:run = InputTask[Unit]
[info]   +-linkerd-examples/http:configFile = linkerd/examples/http.yaml
[info]   | +-linkerd-examples/http:configuration = http
[info]   |
[info]   +-linkerd-examples/http:runtimeConfiguration = bundle
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

#### JS tests ####

See the [javascript readme](/admin/src/main/resources/io/buoyant/admin/README.md).

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
$ docker run -p 4140:4140 -p 9990:9990 -v /absolute/path/to/myapp:/myapp buoyantio/linkerd:0.9.1-SNAPSHOT /myapp/linkerd.yml
```

For local testing convenience, we supply a config that routes to a single
backend on _localhost:8080_.

```
$ docker run -p 4140:4140 -p 9990:9990 -v /path/to/linkerd/linkerd/examples:/config buoyantio/linkerd:0.9.1-SNAPSHOT /config/static_namer.yaml
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
$ namerd/target/scala-2.11/namerd-0.9.1-SNAPSHOT-dcos-exec namerd/examples/zk.yaml
```

##### Run assembly script in docker #####

```bash
$ ./sbt namerd/dcos:docker
$ docker run -p 2181:2181 -p 4180:4180 -v /path/to/repo:/myapp -w /myapp buoyantio/namerd:0.9.1-SNAPSHOT-dcos namerd/examples/zk.yaml
```

[funsuite]: http://www.scalatest.org/getting_started_with_fun_suite
[l5d-ci]: https://circleci.com/gh/linkerd/linkerd
[sbt]: http://www.scala-sbt.org/
[scalatest]: http://www.scalatest.org/
