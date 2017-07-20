# linkerd #

[linkerd.io](https://linkerd.io)

## Quickstart ##

Before starting on, you'll need to install protoc 3.0+ either via a package manager like homebrew, or by downloading a binary from the protobuf release page: https://github.com/google/protobuf/releases.

To get up and running quickly, run:

```
$ ./sbt linkerd-examples/http:run
...
I 0201 21:17:41.992 THREAD52: serving http on localhost/127.0.0.1:4140
```

Then, you may browse to http://localhost:9990 to see an admin
dashboard. Running `curl -X POST http://localhost:9990/admin/shutdown` will
initiate a graceful shutdown of a running router.


### Running ###

The `linkerd` project's packaging configurations may also be used to
run linkerd locally, e.g.:

```
> linkerd/bundle:run path/to/config.yml
```

#### Example configurations ####

Furthermore, the `linkerd/examples` project contains example configurations
that may be quickly started:

```
> linkerd-examples/http:run
```

As additional configuration files are added into `examples/*.yaml`,
these configurations will be discovered by sbt and added as
configurations.
