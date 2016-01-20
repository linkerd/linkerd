# Linker Configuration #

A linker must be provided a configuration as a command-line argument.
The configuration file may be a local file path or `-` to indicate
that the configuration should be read from the standard input.

The configuration may be specified as a JSON or YAML object as
described below.

## Example ##

```yaml
baseDtab: |
  /host     => /$/io.buoyant.fs;
  /method   => /$/io.buoyant.http.anyMethodPfx/host;
  /http/1.1 => /method;
  /ext/http => /host/web;

routers:
- protocol: http
  httpUriInDst: true

- protocol: http
  servers:
  - port: 8080
    ip: 0.0.0.0
    tls:
      certPath: /foo/cert
      keyPath: /foo/key
  timeoutMs: 1000
  dstPrefix: /ext/http

- protocol: thrift
  thriftFramed: false
  thriftMethodInDst: true
  baseDtab: |
    /thrift => /$/io.buoyant.fs/thrift;
```

There are no requirements on field ordering, though it's generally
good style to start a router with the _protocol_.

The most minimal configuration looks something like the following,
which forwards all requests on localhost:8080 to localhost:8888.

```yaml
routers:
- protocol: http
  baseDtab: /http => /$/inet/127.1/8888
  servers:
  - port: 8080
```

## Structure ##

All configurations must define a **routers** key, the value of which
must be an array of router configurations.

Additionally, any of the [basic router params](#basic-router-params)
may be specified in the top-level object as defaults.

### Routers ###

Each router must be an object with the following params:

* **protocol** -- a protocol name must match one of the loaded configuration plugins (e.g. _http_, _mux_).
* [basic router param](#basic-router-params) or [protocol-specific router param](#proto-router-params)
* **servers** -- a list of server objects with the following params:
  * [basic server params](#basic-server-params) or [protocol-specific server params](#proto-server-params)

<a name="basic-router-params">
## Basic router parameters ##

* *label* -- The name of the router (in stats and the admin ui). If
not specified, the protocol name is used.
* *baseDtab* -- Sets the base delegation table (ignoring any prior
dtab settings).
* *dstPrefix* -- A path prefix to be used on request
destinations. Protocols may set defaults.
* *failFast* -- If `true`, connection failures are punished more
aggressively. Should not be used with small destination pools.
* *timeoutMs* -- Per-request timeout in milliseconds.

<!-- TODO router capacity  -->

<a name="proto-router-params">
## Protocol-specific router parameters ##

### http ###

The default _dstPrefix_ is `/http` so that requests
receive destinations in the form _/http/VERSION/METHOD/HOST_.

* *httpAccessLog* -- Sets the access log path.  If not specified, no
access log is written.
* *httpUriInDst* -- If `true` http paths are appended to destinations
(to allow path-prefix routing)

### mux ###

The default _dstPrefix_ is `/mux`, which is appended with each mux
request's destination.

### thrift ###

The default _dstPrefix_ is `/thrift`.

* *thriftFramed* -- if `true`, a framed thrift transport is used; otherwise, a buffered transport is used.

* *thriftMethodInDst* -- if `true`, thrift method names are appended to destinations.

<a name="basic-server-params">
## Basic server parameters ##

* *label* -- The name of the server (in stats and the admin ui)
* *port* -- The TCP port number. Protocols may provide default
values. If no default is provided, the port parameter is required.
* *ip* -- The local IP address.  By default, the loopback address is
used.  A value like `0.0.0.0` configures the server to listen on all
local IPv4 interfaces.
* *tls* -- The server will serve over TLS if this parameter is provided.  It must be an object containing keys:
** *certPath* -- File path to the TLS certificate file
** *keyPath* -- File path to the TLS key file

<a name="proto-server-params">
## Protocol-specific server parameters ##

### http ###

The default _port_ is 4140.

### mux ###

The default _port_ is 4141.

### thrift ###

The default _port_ is 4114.

* *thriftFramed* -- if `true`, a framed thrift transport is used; otherwise, a buffered transport is used.

## Delegation Tables ##

A delegation table (*dtab*) expresses a set of linking rules that
rewrites a destination path to a concrete cluster.

...

### Service discovery ###

<!-- TODO explain how service discovery backends are exported via Namers -->
