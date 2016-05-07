# Configuration

linkerd's configuration is controlled via config file, which must be provided
as a command-line argument. It may be a local file path or `-` to
indicate that the configuration should be read from the standard input.
For convenience, the release package includes a default `linkerd.yaml` file in
the `config/` directory.

## File Format

The configuration may be specified as a JSON or YAML object, as described
below.

```yaml
admin:
  port: 9990

namers:
- kind: io.l5d.fs
  rootDir: disco

routers:
- protocol: http
  label: int-http
  baseDtab: |
    /host       => /io.l5d.fs;
    /http/1.1/* => /host;
  identifier:
    kind: default
    httpUriInDst: true
  servers:
  - port: 4140
    ip: 0.0.0.0

- protocol: http
  label: ext-http
  dstPrefix: /ext/http
  baseDtab: |
    /ext/http/1.1/*/* => /io.l5d.fs/web;
  servers:
  - port: 8080
    ip: 0.0.0.0
    tls:
      certPath: /foo/cert.pem
      keyPath: /foo/key.pem
  client:
    tls:
      kind: io.l5d.clientTls.static
      commonName: foo
      caCertPath: /foo/caCert.pem
    loadBalancer:
      kind: ewma
      enableProbation: false
  timeoutMs: 1000

- protocol: thrift
  servers:
  - port: 8081
    ip: 0.0.0.0
    thriftFramed: true
  client:
    thriftFramed: true
  thriftMethodInDst: false
  baseDtab: |
    /thrift => /io.l5d.fs/thrift;
```

There are no requirements on field ordering, though it's generally
good style to start a router with the _protocol_.

The most minimal configuration looks something like the following,
which forwards all requests on `localhost:8080` to `localhost:8888`.

```yaml
routers:
- protocol: http
  baseDtab: /http => /$/inet/127.1/8888
  servers:
  - port: 8080
```

## Administrative interface

linkerd supports an administrative interface, both as a web ui and a collection
of json endpoints. The exposed admin port is configurable via a top-level
`admin` section:

* *admin* -- Config section for the admin interface, contains keys:
  * *port* -- Port for the admin interface (default is `9990`)

For example:
```yaml
admin:
  port: 9990
```

## Routers

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
  * [HTTP/1.1](#protocol-http)
  * [Thrift](#protocol-thrift)
  * [Mux](#protocol-mux) (experimental)
* [basic router params](#basic-router-params) or protocol-specific router params
* *servers* -- a list of server objects with the following params:
  * [basic server params](#basic-server-params) or protocol-specific server params
* *client* -- an object containing [basic client params](#basic-client-params)
  or protocol-specific client params
* *interpreter* (optional) -- an object determining what module will be used to process destinations,
  with the following params:
  * *kind* -- the name of the interpreter module to be used (default: "default").
  Currently only the _default_ interpreter is supported; this interpreter resolves names via the
  configured [`namers`](#naming), with a fallback to the default Finagle `Namer.Global` that
  handles paths of the form `/$/`.
  * protocol-specific module params, if any (the _default_ module has none)

<a name="basic-router-params"></a>
### Basic router parameters

* *label* -- The name of the router (in stats and the admin ui). (default: the
  protocol name)
* *baseDtab* -- Sets the base delegation table. See [Routing](#configuring-routing) for
  more. (default: an empty dtab)
* *dstPrefix* -- A path prefix to be used on request destinations.
  (default is protocol dependent)
* *failFast* -- If `true`, connection failures are punished more aggressively.
  Should not be used with small destination pools. (default: false)
* *timeoutMs* -- Per-request timeout in milliseconds. (default: no timeout)
* *bindingTimeoutMs* -- Optional.  The maximum amount of time in milliseconds to
  spend binding a path.  (default: 10 seconds)

<!-- TODO router capacity  -->

<a name="basic-server-params"></a>
### Basic server parameters

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

<a name="basic-client-params"></a>
### Basic client parameters

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
* *responseClassifier* -- Optional. A (sometimes protocol-specific)
  module that determines which responses should be considered failures
  and, of those, which should be considered [retryable](#retries). By
  default, connection-level errors are considered failures and those
  where the request has not been written are considered retryable.
  * *kind* -- Indicates the response classifier moduel

#### TLS

* *tls* -- Optional.  The router will make requests using TLS if this parameter
is provided.  It must be an object containing keys:
  * *kind* -- One of the supported TlsClientInitializer plugins, by
  fully-qualified class name.
  * Any options specific to the plugin

Current TLS plugins include:

##### io.l5d.clientTls.noValidation

Skip hostname validation.  This is unsafe.

##### io.l5d.clientTls.static

Use a single common name for all TLS requests.  This assumes that all servers
that the router connects to all use the same TLS cert (or all use certs
generated with the same common name).  This plugin supports the following
options:

* *commonName* -- Required.  The common name to use for all TLS requests.
* *caCertPath* -- Optional.  Use the given CA cert for common name validation.

##### io.l5d.clientTls.boundPath

Determine the common name based on the destination bound path.  This plugin
supports the following options:

* *caCertPath* -- Optional.  Use the given CA cert for common name validation.
* *names* -- Required.  A list of name matchers which each must be an object
  containing:
  * *prefix* -- A path prefix.  All destinations which match this prefix
    will use this entry to determine the common name.  Wildcards and variable
    capture are allowed (see: `io.buoyant.linkerd.util.PathMatcher`)
  * *commonNamePattern* -- The common name to use for destinations matching
    the above prefix.  Variables captured in the prefix may be used in this
    string.
* *strict* -- Optional. When true, paths that fail to match any prefixes throw
    an exception. Defaults to true.

For example,

```yaml
kind: io.l5d.clientTls.boundPath
caCertPath: /foo/cacert.pem
names:
- prefix: "/io.l5d.fs/{host}"
  commonNamePattern: "{host}.buoyant.io"
 strict: false
```

#### Load Balancer

* *loadBalancer* -- Optional.  Specifies a load balancer to use.  It must be an
object containing keys:
  * *kind* -- One of the supported load balancers.
  * *enableProbation* -- Optional.  Controls whether endpoints are eagerly evicted from
    service discovery. (default: true)
    See Finagle's [LoadBalancerFactory.EnableProbation](https://github.com/twitter/finagle/blob/develop/finagle-core/src/main/scala/com/twitter/finagle/loadbalancer/LoadBalancerFactory.scala#L28)
  * Any options specific to the load balancer.

If unspecified, p2c is used.

Current load balancers include:

[p2c]: https://twitter.github.io/finagle/guide/Clients.html#power-of-two-choices-p2c-least-loaded
[ewma]: https://twitter.github.io/finagle/guide/Clients.html#power-of-two-choices-p2c-peak-ewma
[aperture]: https://twitter.github.io/finagle/guide/Clients.html#aperture-least-loaded
[heap]: https://twitter.github.io/finagle/guide/Clients.html#heap-least-loaded

##### [p2c][p2c]

p2c supports the following options (see [here][p2c] for option semantics and defaults):

* *maxEffort* -- Optional.

##### [ewma][ewma]

ewma supports the following options (see [here][ewma] for option semantics and defaults):

* *maxEffort* -- Optional.
* *decayTimeMs* -- Optional.

##### [aperture][aperture]

aperture supports the following options (see [here][aperture] for option semantics and defaults):

* *maxEffort* -- Optional.
* *smoothWindowMs* -- Optional.
* *lowLoad* -- Optional.
* *highLoad* -- Optional.
* *minAperture* -- Optional.

##### [heap][heap]

heap does not support any options.

<a name="retries"></a>
#### Retries

linkerd can automatically retry requests on certain failures (for
example, connection errors).

* *retries* -- Optional. A retry policy for all clients created by
  this router.
  * *budget* -- Optional. Determines _how many_ failed requests are
    eligible to be retried.
    * *minRetriesPerSec* -- Optional. The minimum rate of retries
      allowed in order to accommodate clients that have just started
      issuing requests as well as clients that do not issue many
      requests per window. Must be non-negative and if `0`, then no
      reserve is given. (Default: 10)
    * *percentCanRetry* -- Optional. The percentage of calls that can
      be retried. This is in addition to any retries allowed for via
      `minRetriesPerSec`.  Must be >= 0 and <= 1000. As an example, if
      `0.1` is used, then for every 10 non-retry calls , 1 retry will
      be allowed. If `2.0` is used then every non-retry call will
      allow 2 retries. (Default: 0.2)
    * *ttlSecs* -- Optional. The amount of time in seconds that
      successful calls are considered when calculating retry budgets
      (Default: 10)
  * *backoff* -- Optional. Determines which backoff algorithm should
    be used (see below).
    * *kind* -- The name of a backoff algorithm. Either _constant_ or
      _jittered_.

The _constant_ backoff policy takes a single configuration parameter:
* _ms_ -- The number of milliseconds to wait before each retry.

The _jittered_ backoff policy uses a
[decorrelated jitter](http://www.awsarchitectureblog.com/2015/03/backoff.html)
backoff algorithm and requires two configuration parameters:
* _minMs_ -- The minimum number of milliseconds to wait before each retry.
* _maxMs_ -- The maximum number of milliseconds to wait before each retry.

<a name="protocol-http"></a>
### HTTP/1.1 protocol parameters

Router configuration options include:
* *httpAccessLog* -- Sets the access log path.  If not specified, no
access log is written.
* *identifier* -- [Http-specific identifier](#protocol-http-identifiers) (default:
default)

The default _dstPrefix_ is `/http`
The default server _port_ is 4140

As an example, here's an http router config that routes all `POST`
requests to 8091 and all other requests to 8081:

```yaml
routers:
- protocol: http
  label: split-get-and-post
  baseDtab: |
    /method/*    => /$/inet/127.1/8081;
    /method/POST => /$/inet/127.1/8091;
    /http/1.1    => /method;
  servers:
    port: 5000
```
<a name="protocol-http-identifiers"></a>
#### HTTP/1.1 Identifiers

Identifiers are objects responsible for creating logical names from an incoming
request with the following parameters:

* *kind* -- One of the supported identifier plugins, by fully-qualified class
 name. Current plugins include:
  * *default*
* *httpUriInDst* -- If `true` http paths are appended to destinations. This allows
 path-prefix routing. (default: false)
* any other identifier-specific parameters

##### default

HTTP requests are routed by a combination of Host header, method, and URI.
Specifically, HTTP/1.0 logical names are of the form:
```
  dstPrefix / "1.0" / method [/ uri* ]
```
and HTTP/1.1 logical names are of the form:
```
  dstPrefix / "1.1" / method / host [/ uri* ]
```

In both cases, `uri` is only considered a part
of the logical name if the config option `httpUriInDst` is true.


#### HTTP Response Classifiers

Response classifiers determine which HTTP responses are considered to
be failures (for the purposes of success rate calculation) and which
of these responses may be [retried](#retries). By default, the
_nonRetryable5XX_ classifier is used.

##### nonRetryable5XX

All 5XX responses are considered to be failures and none of these
requests are considered to be retryable.

##### retryableRead5XX

All 5XX responses are considered to be failures. However, `GET`,
`HEAD`, `OPTIONS`, and `TRACE` requests may be retried automatically.

##### retryableIdempotent5XX

Like _retryableRead5XX_, but `PUT` and `DELETE` requests may also be
retried.

<a name="protocol-thrift"></a>
### Thrift protocol parameters

Since the Thrift protocol does not encode a destination name in the message
itself, routing must be done per port. This implies one port per Thrift
service. For out-of-the-box configuration, this means that the contents of
`disco/thrift` will be treated as a newline-delimited list of `host:port`
combinations for a specific thrift service.

The default _dstPrefix_ is `/thrift`.

* *thriftMethodInDst* -- if `true`, thrift method names are appended to
  destinations for outgoing requests. (default: false)

Thrift servers define additional parameters:

* *thriftFramed* -- if `true`, a framed thrift transport is used for incoming
  requests; otherwise, a buffered transport is used. Typically this setting
  matches the router's `thriftFramed` param. (default: true)
* *thriftProtocol* -- allows the thrift protocol to be chosen;
   currently supports 'binary' for `TBinaryProtocol` (default) and
   'compact' for `TCompactProtocol`. Typically this setting matches
   the router's client `thriftProtocol` param.

The default server _port_ is 4114.

Thrift also supports additional *client* parameters:

* *thriftFramed* -- if `true`, a framed thrift transport is used for outgoing
  requests; otherwise, a buffered transport is used. Typically this setting
  matches the router's servers' `thriftFramed` param. (default: true)
* *thriftProtocol* -- allows the thrift protocol to be chosen;
   currently supports `binary` for `TBinaryProtocol` (default) and
   `compact` for `TCompactProtocol`. Typically this setting matches
   the router's servers' `thriftProtocol` param.
* *attemptTTwitterUpgrade* -- controls whether thrift protocol upgrade should be
   attempted.  (default: true)

As an example: Here's a thrift router configuration that routes thrift--via
buffered transport using the TCompactProtocol --from port 4004 to port 5005

```yaml
routers:
- protocol: thrift
  label: port-shifter
  baseDtab: |
    /thrift => /$/inet/127.1/5005;
  servers:
  - port: 4004
    ip: 0.0.0.0
    thriftFramed: false
    thriftProtocol: compact
  client:
    thriftFramed: false
    thriftProtocol: compact
```

<a name="protocol-mux"></a>
### Mux protocol parameters (experimental)

linkerd experimentally supports the [mux
protocol](http://twitter.github.io/finagle/guide/Protocols.html#mux).

The default _dstPrefix_ is `/mux`.
The default server _port_ is 4141.

As an example: Here's a mux router configuration that routes requests to port 9001

```yaml

routers:
- protocol: mux
  label: power-level-router
  dstPrefix: /overNineThousand
  baseDtab: |
    /overNineThousand => /$/inet/127.0.1/9001;
```

<a name="tracing"></a>
## Tracers

Requests that are routed by linkerd are also traceable using Finagle's built-in
tracing instrumentation. Trace data can be exported from a linkerd process by
configuring tracers via a top-level `tracers` section:

* *tracers* --
  * *kind* -- One of the supported tracers, by fully-qualified class name.
  * *debugTrace* -- Print all traces to the console. Note this overrides the
    global `-com.twitter.finagle.tracing.debugTrace` flag, and will default to
    that flag if not set here.
  * Any options specific to the tracer

Current tracers include:

### io.l5d.zipkin

Finagle's [zipkin-tracer](https://github.com/twitter/finagle/tree/develop/finagle-zipkin).

* *host* -- Optional. Host to send trace data to. (default: localhost)
* *port* -- Optional. Port to send trace data to. (default: 9410)
* *sampleRate* -- Optional. How much data to collect. (default: 0.001)

For example:

```yaml
tracers:
- kind: io.l5d.zipkin
  host: localhost
  port: 9410
  sampleRate: 0.02
  debugTrace: true
```

<a name="naming"></a>
## Service discovery and naming

linkerd supports a variety of common service discovery backends, including
ZooKeeper and Consul. linkerd provides abstractions on top of service discovery
lookups that allow the use of arbitrary numbers of service discovery backends,
and for precedence and failover rules to be expressed between them. This logic
is governed by the [routing](#basic-router-params) configuration.

Naming and service discovery are configured via the `namers` section of the
configuration file. In this file, `namers` is an array of objects, consisting
of the following parameters:

* *kind* -- One of the supported namer plugins, by fully-qualified class name.
  Current plugins include:
  * *io.l5d.fs*: [File-based service discovery](#disco-file)
  * *io.l5d.serversets*: [ZooKeeper ServerSets service discovery](#zookeeper)
  * *io.l5d.experimental.consul*: [Consul service discovery](#consul) (**experimental**)
  * *io.l5d.experimental.k8s*: [Kubernetes service discovery](#disco-k8s) (**experimental**)
  * *io.l5d.experimental.marathon*: [Marathon service discovery](#marathon) (**experimental**)
* *prefix* -- This namer will resolve names beginning with this prefix. See
  [Configuring routing](#configuring-routing) for more on names. Some namers may
  configure a default prefix; see the specific namer section for details.
* *namer-specific parameters*.

<a name="disco-file"></a>
### File-based service discovery

linkerd ships with a simple file-based service discovery mechanism, called the
*file-based namer*. This system is intended to act as a structured form of
basic host lists.

While simple, the file-based namer is a full-fledged service discovery system,
and can be useful in production systems where host configurations are largely
static. It can act as an upgrade path for the introduction of an external
service discovery system, since application code will be isolated from these
changes. Finally, when chained with precedence rules, the file-based namer can
be a convenient way to add local service discovery overrides for debugging or
experimentation.

This service discovery mechanism is tied to the directory set by the
`namers/rootDir` key in `config.yaml`. This directory must be on the local
filesystem and relative to linkerd's start path. Every file in this directory
corresponds to a service, where the name of the file is the service's _concrete
name_, and the contents of the file must be a newline-delimited set of
addresses.

For example, the directory might look like this:

```bash
$ ls disco/
apps    users   web
```
And the contents of the files might look like this:

```bash
$ cat config/web
192.0.2.220 8080
192.0.2.105 8080
192.0.2.210 8080
```

linkerd watches all files in this directory, so files can be added, removed, or
updated, and linkerd will pick up the changes automatically.

The file-based namer is configured with kind `io.l5d.fs`, and these parameters:

* *rootDir* -- the directory containing name files as described above.

For example:
```yaml
namers:
- kind: io.l5d.fs
  rootDir: disco
```

The default _prefix_ for the file-based namer is `io.l5d.fs`.

Once configured, to use the file-based namer, you must reference it in
the dtab. For example:
```
baseDtab: |
  /http/1.1/* => /io.l5d.fs
```

<a name="zookeeper"></a>
### ZooKeeper ServerSets service discovery

linkerd provides support for [ZooKeeper
ServerSets](https://twitter.github.io/commons/apidocs/com/twitter/common/zookeeper/ServerSet.html).

The ServerSets namer is configured with kind `io.l5d.serversets`, and these parameters:

* *zkAddrs* -- list of ZooKeeper hosts.
* *host* --  the ZooKeeper host.
* *port* --  the ZooKeeper port.

For example:
```yaml
namers:
- kind: io.l5d.serversets
  zkAddrs:
  - host: 127.0.0.1
    port: 2181
```

The default _prefix_ is `io.l5d.serversets`.

Once configured, to use the ServerSets namer, you must reference it in
the dtab. For example:
```
baseDtab: |
  /http/1.1/* => /io.l5d.serversets/discovery/prod;
```

<a name="consul"></a>
### Consul service discovery (experimental)

linkerd provides support for service discovery via
[Consul](https://www.consul.io/). Note that this support is still considered
experimental.

The Consul namer is configured with kind `io.l5d.experimental.consul`, and these parameters:

* *host* --  the Consul host. (default: localhost)
* *port* --  the Consul port. (default: 8500)

For example:
```yaml
namers:
- kind: io.l5d.experimental.consul
  host: 127.0.0.1
  port: 2181
```

The default _prefix_ is `io.l5d.consul`. (Note that this is *different* from
the name in the configuration block.)

Once configured, to use the Consul namer, you must reference it in
the dtab. The Consul namer takes one parameter in its path, which is the Consul
datacenter. For example:
```
baseDtab: |
  /http/1.1/* => /io.l5d.consul/dc1;
```

<a name="disco-k8s"></a>
### Kubernetes service discovery (experimental)

linkerd provides support for service discovery via
[Kubernetes](https://k8s.io/). Note that this support is still considered
experimental.

The Kubernetes namer is configured with kind `io.l5d.experimental.k8s`, and these parameters:

* *host* -- the Kubernetes master host. (default: kubernetes.default.svc.cluster.local)
* *port* -- the Kubernetes master port. (default: 443)
* *tls* -- Whether TLS should be used in communicating with the Kubernetes master. (default: true)
* *tlsWithoutValidation* -- Whether certificate-checking should be disabled. (default: false)
* *authTokenFile* -- Path to a file containing the Kubernetes master's authorization token.
  (default: /var/run/secrets/kubernetes.io/serviceaccount/token)

For example:
```yaml
namers:
- kind: io.l5d.experimental.k8s
  host: kubernetes.default.svc.cluster.local
  port: 443
  tls: true
  authTokenFile: /var/run/secrets/kubernetes.io/serviceaccount/token
```

The default _prefix_ is `io.l5d.k8s`. (Note that this is *different* from
the name in the configuration block.)

The Kubernetes namer takes three path components: `namespace`, `port-name` and
`svc-name`:

* namespace: the Kubernetes namespace.
* port-name: the port name.
* svc-name: the name of the service.

Once configured, to use the Kubernetes namer, you must reference it in
the dtab.
```
baseDtab: |
  /http/1.1/* => /io.l5d.k8s/prod/http;
```

<a name="marathon"></a>
### Marathon service discovery (experimental)

linkerd provides support for service discovery via
[Marathon](https://mesosphere.github.io/marathon/). Note that this support is still considered
experimental.

The Marathon namer is configured with kind `io.l5d.experimental.marathon`, and these parameters:

* *host* -- the Marathon master host. (default: marathon.mesos)
* *port* -- the Marathon master port. (default: 80)
* *uriPrefix* -- the Marathon API prefix. (default: empty string). This prefix
  depends on your Marathon configuration. For example, running Marathon
  locally, the API is avaiable at `localhost:8080/v2/`, while the default setup
  on AWS/DCOS is `$(dcos config show core.dcos_url)/marathon/v2/apps`.
* *ttlMs* -- the polling timeout in milliseconds against the marathon API
  (default: 5000)

For example:
```yaml
namers:
- kind:      io.l5d.experimental.marathon
  prefix:    /io.l5d.marathon
  host:      marathon.mesos
  port:      80
  uriPrefix: /marathon
  ttlMs:     500
```

The default _prefix_ is `io.l5d.marathon`. (Note that this is *different* from
the name in the configuration block.)

The Marathon namer takes any number of path components. The path should
correspond to the app id of a marathon application. For example, the app with
id "/users" can be reached with `/io.l5d.marathon/users`. Likewise, the app
with id "/appgroup/usergroup/users" can be reached with
`/io.l5d.marathon/appgroup/usergroup/users`.

Once configured, to use the Marathon namer, you must reference it in
the dtab.
```
baseDtab: |
  /marathonId => /io.l5d.marathon;
  /host       => /$/io.buoyant.http.domainToPathPfx/marathonId;
  /http/1.1/* => /host;
```

<a name="configuring-routing"></a>
## Configuring routing

Routing rules determine the mapping between a service's logical name and a
concrete name. Routing is described via a "delegation table", or **dtab**. As
noted in [basic router parameters](#basic-router-params), this is configured
via the `baseDtab` section of the configuration file.

<a name="routing-overrides"></a>
### Per-request routing overrides

For HTTP calls, the `Dtab-local` header is interpreted by linkerd as an
additional rule to be appended to the base dtab. Since dtab rules are applied
from bottom to top, this allows overriding of the routing rules specified
`baseDtab` for this request.

Note that linkerd copies this header to the outgoing (proxied) RPC call. In
this manner, override logic can be propagated through the entire call graph for
the request.
