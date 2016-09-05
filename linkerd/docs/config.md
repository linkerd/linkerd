# Introduction

> A minimal linkerd configuration example, which forwards all requests on `localhost:8080` to `localhost:8888`



```yaml
routers:
- protocol: http
  baseDtab: /http => /$/inet/127.1/8888
  servers:
  - port: 8080
```

linkerd's configuration is controlled via config file, which must be provided
as a command-line argument. It may be a local file path or `-` to
indicate that the configuration should be read from the standard input.
For convenience, the release package includes a default `linkerd.yaml` file in
the `config/` directory.

### File Format

> A more complex linkerd config example

```yaml
admin:
  port: 9990

routers:
- protocol: http
  label: int-http
  baseDtab: |
    /host       => /#/io.l5d.fs;
    /http/1.1/* => /host;
  identifier:
    kind: io.l5d.methodAndHost
    httpUriInDst: true
  servers:
  - port: 4140
    ip: 0.0.0.0

- protocol: thrift
  servers:
  - port: 8081
    ip: 0.0.0.0
    thriftFramed: true
  client:
    thriftFramed: true
  thriftMethodInDst: false
  baseDtab: |
    /thrift => /#/io.l5d.fs/thrift;

namers:
- kind: io.l5d.fs
  rootDir: disco

tracers:
- kind: io.l5d.zipkin
  sampleRate: 0.02
```

The configuration may be specified as a JSON or YAML object, as described
below.  Four top level keys are supported:

* [Admin](#admin)
* [Routers](#routers)
* [Namers](#namers)
* [Tracers](#tracers)

There are no requirements on field ordering, though it's generally
good style to start a router with the _protocol_.

<a name="admin"></a>
## Administrative interface

```yaml
admin:
  port: 9990
```

linkerd supports an administrative interface, both as a web ui and a collection
of json endpoints. The exposed admin port is configurable via a top-level
`admin` section

* *admin* -- Config section for the admin interface, contains keys:
  * *port* -- Port for the admin interface (default is `9990`)

<a name="routers"></a>
## Routers

```yaml
routers:
- protocol: http
  servers: ...
  client: ...
  interpreter: ...
  announcers: ...
```

All configurations must define a **routers** key, the value of which
must be an array of router configurations. Each router implements RPC
for a supported protocol. (linkerd doesn't need to understand the payload in an
RPC call, but it does need to know enough about the protocol to determine the
logical name of the destination.)

Routers also include **servers**, which define their entry points, and
**client**, which configures how clients are built.

Additionally, any of the [basic router params](#basic-router-params)
may be specified in the top-level object as defaults.

Each router must be configured as an object with the following params:

* *protocol* -- a protocol name must match one of the loaded configuration plugins (e.g. _http_, _mux_).
  linkerd currently supports the following protocols:
  * [HTTP/1.1](#http-1-1-protocol), by using the value *http*;
  * [Thrift](#thrift-protocol), by using the value *thrift*; and
  * [Mux](#mux-protocol-experimental) (experimental), by using the value *mux*.
* [basic router params](#basic-router-params) or protocol-specific router params
* *servers* -- a list of server objects with the following params:
  * [basic server params](#basic-server-params) or protocol-specific server params
* *client* -- an object containing [basic client params](#basic-client-params)
  or protocol-specific client params
* *interpreter* (optional) -- an
  [interpreter](#interpreter) object determining what module will be used to
  process destinations.  (default: default)
  * protocol-specific module params, if any (the _default_ module has none)
* *announcers* (optional) -- a list of service discovery
  [announcers](#announcers) that servers can announce to.

<a name="basic-router-params"></a>
### Basic router parameters

```yaml
routers:
- protocol: http
  label: myPackIce
  dstPrefix: /walruses/http
  baseDtab: |
    /host                => /#/io.l5d.fs;
    /walruses/http/1.1/* => /host;
  failFast: false
  timeoutMs: 10000
  bindingTimeoutMs: 5000
  bindingCache:
    paths: 100
    trees: 100
    bounds: 100
    clients: 100
  responseClassifier: io.l5d.nonRetryable5XX
```

* *label* -- The name of the router (in stats and the admin ui). (default: the
  protocol name)
* *baseDtab* -- Sets the base delegation table. See
  [dtabs](https://linkerd.io/doc/dtabs/) for more. (default: an empty dtab)
* *dstPrefix* -- A path prefix to be used on request destinations.
  (default is protocol dependent)
* *failFast* -- If `true`, connection failures are punished more aggressively.
  Should not be used with small destination pools. (default: false)
* *timeoutMs* -- Per-request timeout in milliseconds. (default: no timeout)
* *bindingTimeoutMs* -- Optional.  The maximum amount of time in milliseconds to
  spend binding a path.  (default: 10 seconds)
* *bindingCache* -- Optional.  Configure the size of binding cache.  It must be
  an object containing keys:
  * *paths* -- Optional.  Size of the path cache.  (default: 100)
  * *trees* -- Optional.  Size of the tree cache.  (default: 100)
  * *bounds* -- Optional.  Size of the bound cache.  (default: 100)
  * *clients* -- Optional.  Size of the client cache.  (default: 10)
* *responseClassifier* -- Optional. A
  (sometimes protocol-specific) [response classifier](#http-response-classifiers)
  that determines which responses should be considered failures and, of those,
  which should be considered [retryable](#retries).
  (default: _io.l5d.nonRetryable5XX_)

<a name="basic-server-params"></a>
### Basic server parameters

```yaml
servers:
- port: 8080
  ip: 0.0.0.0
  tls:
    certPath: /foo/cert.pem
    keyPath: /foo/key.pem
  maxConcurrentRequests: 1000
  announce:
    - /#/io.l5d.fs/web
```

* *port* -- The TCP port number. Protocols may provide default
values. If no default is provided, the port parameter is required.
* *ip* -- The local IP address.  By default, the loopback address is
used.  A value like `0.0.0.0` configures the server to listen on all
local IPv4 interfaces.
* *tls* -- The server will serve over TLS if this parameter is provided.
  It must be an object containing keys:
  * *certPath* -- File path to the TLS certificate file
  * *keyPath* -- File path to the TLS key file
* *maxConcurrentRequests* -- Optional.  The maximum number of concurrent
requests the server will accept.  (default: unlimited)
<a name="announce"></a>
* *announce* -- Optional.  A list of concrete names to announce using the
  router's [announcers](#announcers).

<a name="basic-client-params"></a>
### Basic client parameters

```yaml
client:
  hostConnectionPool:
    minSize: 0
    maxSize: 1000
    idleTimeMs: 10000
    maxWaiters: 5000
  tls:
    kind: io.l5d.noValidation
    commonName: foo
    caCertPath: /foo/caCert.pem
  loadBalancer:
    kind: ewma
    enableProbation: false
  retries:
    backoff:
      kind: jittered
      minMs: 10
      maxMs: 10000
```

* *hostConnectionPool* -- Optional.  Configure the number of connections to
maintain to each destination host.  It must be an object containing keys:
  * *minSize* -- Optional. The minimum number of connections to maintain to each
  host.  (default: 0)
  * *maxSize* -- Optional.  The maximum number of connections to maintain to
  each host.  (default: Int.MaxValue)
  * *idleTimeMs* -- Optional.  The amount of idle time for which a connection is
  cached in milliseconds.  (default: forever)
  * *maxWaiters* -- Optional.  The maximum number of connection requests that
  are queued when the connection concurrency exceeds maxSize.  (default:
  Int.MaxValue)
* *tls* -- Optional.  The router will make requests
  using TLS if this parameter is provided.  It must be a
  [client TLS](#client-tls) object.
* *loadBalancer* -- Optional.  A
  [load balancer](#load-balancer) object.  (default: p2c)
* *retries* -- Optional. A [retry policy](#retries) for all clients created by
  this router.

<a name="service-discovery-and-naming"></a>
## Service discovery and naming

```yaml
namers:
- kind: io.l5d.fs
  rootDir: disco
```

linkerd supports a variety of common service discovery backends, including
ZooKeeper and Consul. linkerd provides abstractions on top of service discovery
lookups that allow the use of arbitrary numbers of service discovery backends,
and for precedence and failover rules to be expressed between them. This logic
is governed by the [routing](#basic-router-params) configuration.

Naming and service discovery are configured via the `namers` section of the
configuration file.  A namer acts on paths that start with `/#` followed by the
namer's prefix.

* *namers* -- An array of [namer](#namers) objects.

<a name="tracers"></a>
## Tracers

```yaml
tracers:
- kind: io.l5d.zipkin
  sampleRate: 0.02
```

Requests that are routed by linkerd are also traceable using Finagle's built-in
tracing instrumentation. Trace data can be exported from a linkerd process by
configuring tracers via a top-level `tracers` section:

* *tracers* -- An array of [tracer](#tracers10) objects.
