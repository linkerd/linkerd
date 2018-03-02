# Namerd #

Namerd is a service for managing Linkerd (and finagle) name delegation
(i.e. routing).

```
Application
  |
Request
  |
  V
Linkerd ----logical name-----> Namerd <--> Service Discovery
        <---concrete address--        <--> Dtab Storage
  |
Request
  |
  V
Destination
```

Namerd is a service that resolves logical names to concrete addresses.  By
having Linkerd use Namerd for name delegation, we reduce the load on service
discovery backends and can use Namerd as a central place to control routing
across a fleet of linkers.

Namerd supports pluggable modules for dtab storage, naming and service
discovery, and servable interfaces.  For example, Namerd can be configured to
use ZooKeeper for storage, ZooKeeper Serversets for service discovery, and
a long-poll thrift interface.

## Quickstart ##

Namerd can be run locally with the commands

```
./sbt namerd/examples/basic:run
```
or
```
./sbt 'namerd-main/run path/to/config.yml'
```

## Http Control Interface ##

Namerd provides an http interface to create, update and retrieve Dtabs, as
defined in `HttpControlService`.

For a command-line tool which wraps this interface, try
[namerctl](https://github.com/linkerd/namerctl)

Primary interface endpoints:

```
$ export NAMERD_URL=http://localhost:4180
```

Get all dtab namespaces:

```
$ curl $NAMERD_URL/api/1/dtabs
```

Get a dtab for the `default` namespace:

```
$ curl $NAMERD_URL/api/1/dtabs/default
```

Create or update a dtab namespace:

```
$ curl -v -X PUT -d"/foo => /bar" -H "Content-Type: application/dtab" $NAMERD_URL/api/1/dtabs/baz
```

Evaluate a path against a known dtab namespace:

```
$ curl -v $NAMERD_URL/api/1/delegate/baz?path=/foo
```

Evaluate a path against an arbitrary dtab:

```
$ curl -v "$NAMERD_URL/api/1/delegate?dtab=/foo=>/bar&path=/foo"
```

Resolve a logic or concrete name against a known dtab namespace:

```
$ curl -v $NAMERD_URL/api/1/resolve/baz?path=/foo
```
