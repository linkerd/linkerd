# namerd #

namerd is a service for managing linkerd (and finagle) name delegation
(i.e. routing).

```
Applicaion
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

namerd is a service that resovles logical names to concrete addresses.  By
having linkerd use namerd for name delegation, we reduce the load on service
discovery backends and can use namerd as a central place to control routing
across a fleet of linkers.

namerd supports pluggable modules for dtab storage, naming and service
discovery, and servable interfaces.  For example, namerd can be configured to
use ZooKeeper for storage, ZooKeeper Serversets for service discovery, and
a long-poll thrift interface.

## Quickstart ##

namerd can be run locally with the command

```
./sbt 'namerd-main:run path/to/config.yml'
```
