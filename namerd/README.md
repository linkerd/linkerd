# namerd #

namerd is a service for managing linkerd (and finagle) name delegation
(i.e. routing).

```
Application
  |
Request
  |
  V
linkerd ----logical name-----> namerd <--> Service Discovery
        <---concrete address--        <--> Dtab Storage
  |
Request
  |
  V
Destination
```

namerd is a service that resolves logical names to concrete addresses.  By
having linkerd use namerd for name delegation, we reduce the load on service
discovery backends and can use namerd as a central place to control routing
across a fleet of linkers.

namerd supports pluggable modules for dtab storage, naming and service
discovery, and servable interfaces.  For example, namerd can be configured to
use ZooKeeper for storage, ZooKeeper Serversets for service discovery, and
a long-poll thrift interface.

## Quickstart ##

namerd can be run locally with the commands

```
./sbt namerd-examples/basic:run
```
or
```
./sbt 'namerd-main:run path/to/config.yml'
```

## Http Control Interface ##

namerd provides an http interface to create, update and retrieve Dtabs, as
defined in `HttpControlService`.

For a command-line tool which wraps this interface, try
[namerctl](https://github.com/BuoyantIO/namerctl)

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
