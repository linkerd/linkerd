# How to Contribute #

:balloon: Thank you for contributing to the linkerd project!

Before getting started, please take a moment to sign [Buoyant's CLA][cla].

The following is a set of guidelines for contributing to linkerd on Github.
Please send us a pull request if any of these guidelines seem to be incompatible
with your workflow.

## Building ##

[sbt][sbt] is used to build and test linkerd. Developers should not use a
system-installed version of sbt, and should instead use the `./sbt` script,
which ensures that a compatible version of sbt is available.

Some common sbt build targets:

* `./sbt compile` builds all components (whereas `./sbt "project linkerd"
compile` compiles only the _linkerd_ projects)

* There are three supported test configurations (see
[Testing](#testing) for more details):
    * `test:test` runs unit tests.
    * `e2e:test` runs end-to-end tests
    * `integration:test` runs integration tests

* `./sbt linkerd/assembly` produces a fat-jar containing all library
dependencies into *linkerd/linkerd/target/scala-2.11/linkerd-VERSION.jar*.

* The '_minimal_' sbt configuration, supporting only the `http`
protocol and the `io.l5d.fs` namer, is useful for running linkerd
during development, e.g. `./sbt "linkerd/minimal:run
path/to/config.yaml"`.  See the [config](docs/config.md) page for more
information on configuring linkerd.

* Example configurations are provided in the `examples` directory.  A
basic example may be started with `./sbt examples/http:run`.

## Development ##

Here's the workflow that we're using for ongoing feature development:

1. Fork linkerd on Github
2. Check out the `master` branch
3. Make a feature branch (use `git checkout -b "<username>/new-feature"`)
4. Write code for your feature or bugfix
5. Write tests for your change (see [Testing](#testing))
6. From your branch, make a pull request against `BuoyantIO/linkerd/master`
7. Work with us to get your change reviewed
8. Wait for your change to be pulled into `BuoyantIO/linkerd/master`
9. Merge `BuoyantIO/linkerd/master` into your origin `master`
10. Delete your feature branch

Prior to submitting a pull request, please cleanup the commit history on your
branch such that each commit is self-explanatory, even without the context of
the pull request. In general we encourage one commit per pull request, since
multiple commits could be indicative of multiple features that should be
submitted as separate pull requests.

## Testing ##

Two types of tests are supported, as follows:

* Unit tests are written using the [ScalaTest][scalatest] testing framework, and
specifically the [`FunSuite`][funsuite] mixin, which supports xUnit-like
semantics. All unit test files can be found in the `src/test` directories within
each project.

* End-to-end tests are also written with [`FunSuite`][funsuite], but are more
comprehensive in scope than unit tests. These tests may bind servers on
ephemeral ports, write temporary files to disk, etc. All end-to-end test files
can be found in the `src/e2e` directories within each project. Note that not all
projects have end-to-end tests.

* Integration tests are written similarly, but may use external
services or programs that must be installed outside of `sbt`.

All tests may leverage the `test-util` project, which provides some helpers for
writing tests against Finagle's asynchronous APIs.

Both unit and end-to-end tests are run as part of our [CI][ci] setup.

## Style ##

We generally follow [Effective Scala][es] and the [Scala Style Guide][ssg]. When
in doubt, look around the codebase and see how it's done elsewhere.

Thank you for getting involved!
:heart: Team Buoyant

[cla]: https://buoyant.io/cla/
[sbt]: http://www.scala-sbt.org/
[scalatest]: http://www.scalatest.org/
[funsuite]: http://www.scalatest.org/getting_started_with_fun_suite
[ci]: https://circleci.com/gh/BuoyantIO/linkerd
[es]: https://twitter.github.io/effectivescala/
[ssg]: http://docs.scala-lang.org/style/scaladoc.html
