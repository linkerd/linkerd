# Finagle Etcd client #

## `io.buoyant.etcd` ##

The `io.buoyant.etcd` package contains a generically useful Etcd
client built on finagle-http. The client supports a variety of
asynchronous `key` operations on etcd.  It goes beyond the basic etcd
operations to provide a `watch` utility that models a key's (or
tree's) state as an `com.twitter.util.Activity`.

### TODO ###

- `compareAndDelete` isn't yet implemented.  minor oversight.
- Use residual paths on the client to instrument chrooted key
operations
- Admin/Stats APIs
- move to gRPC api
