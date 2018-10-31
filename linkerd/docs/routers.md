# Routers

All configurations must define a **routers** key, the value of which
must be an array of router configurations. Routers also include **servers**,
which define their entry points, **client**, which configures how clients are
built, and **service**, which configures service level policy.

## Router Parameters

<aside class="notice">
These parameters are available to the router regardless of protocol. Routers may also have protocol-specific parameters.
</aside>

```yaml
routers:
- protocol: http
  servers: ...
  service: ...
  client: ...
  announcers: ...
  bindingCache: ...
  label: myPackIce
  dstPrefix: /walruses/http
  dtab: |
    /host                => /#/io.l5d.fs;
    /walruses/http => /host;
  originator: true
  bindingTimeoutMs: 5000
```

Key | Default Value | Description
--- | ------------- | -----------
protocol | _required_ | Either [`http`](#http-1-1-protocol), [`h2`](#http-2-protocol), [`thrift`](#thrift-protocol), or [`mux`](#mux-protocol-experimental).
servers | _required_ | A list of [server objects](#servers).
announcers | an empty list | A list of service discovery [announcers](#announcers) that servers can announce to.
dtab | an empty dtab | Sets the base delegation table. See [dtabs](https://linkerd.io/doc/dtabs/) for more.
bindingTimeoutMs | 10 seconds | The maximum amount of time in milliseconds to spend binding a path.
bindingCache | see [binding cache](#binding-cache) | Binding cache size configuration.
client | an empty object | A [client configuration](#client-configuration) object.
dstPrefix | protocol dependent | A path prefix to be used on request destinations.
originator | `false` | If `true`, indicates that this router is the first hop for linker-to-linker requests, and reflects that in the router's stats. Useful for deduping linker-to-linker stats.
interpreter | default interpreter | An [interpreter object](#interpreter) determining what module will be used to process destinations.
label | the value of *protocol* | The name of the router (in stats and the admin ui)

### Binding Cache

```yaml
- protocol: http
  servers:
  - port: 9000
  bindingCache:
    paths: 100
    trees: 100
    bounds: 100
    clients: 10
    idleTtlSecs: 3600
```

Key | Default Value | Description
-------------- | -------------- | --------------
paths | `1000` | Max number of paths in the path cache.
trees | `1000` | Max number of trees in the tree cache.
bounds | `1000` | Max number of bounds in the bounds cache.
clients | `1000` | Max number of clients in the clients cache.
idleTtlSecs | 10 minutes | The amount of time, in seconds, to cache idle services and clients before expiring them.

## Server Parameters

<aside class="notice">
These parameters are available to the server regardless of protocol. Servers may also have protocol-specific parameters.
</aside>

```yaml
servers:
- port: 8080
  ip: 0.0.0.0
  tls:
    certPath: /foo/cert.pem
    keyPath: /foo/key.pem
  maxConcurrentRequests: 1000
  announce:
    - /#/io.l5d.serversets/discovery/prod/web
```

Key | Default Value | Description
--- | ------------- | -----------
port | protocol dependent | The TCP port number. Protocols may provide default values. If no default is provided, the port parameter is required.
ip | loopback address | The local IP address. A value like 0.0.0.0 configures the server to listen on all local IPv4 interfaces.
socketOptions | none | Socket options to set for the router interface. See [Socket Options](#socket-options)
tls | no tls | The server will serve over TLS if this parameter is provided. see [TLS](#server-tls).
maxConcurrentRequests | unlimited | The maximum number of concurrent requests the server will accept.
announce | an empty list | A list of concrete names to announce using the router's [announcers](#announcers).
clearContext | `false` | If `true`, all headers that set Linkerd contexts are removed from inbound requests. Useful for servers exposed on untrusted networks.

## Service Configuration

This section defines the policy that Linkerd will use when talking to services.
The structure of this section depends on its `kind`.

Key  | Default Value    | Description
---- | ---------------- | --------------
kind | `io.l5d.global` | Either [io.l5d.global](#global-service-config) or [io.l5d.static](#static-service-config)

### Global Service Config

```yaml
- protocol: http
  service:
    kind: io.l5d.global
    totalTimeoutMs: 500
    retries:
      budget:
        minRetriesPerSec: 5
        percentCanRetry: 0.5
        ttlSecs: 15
      backoff:
        kind: jittered
        minMs: 10
        maxMs: 10000
```

This service configuration allows you to specify [service parameters](#service-parameters)
which will be applied to all services.

### Static Service Config

```yaml
- protocol: http
  service:
    kind: io.l5d.static
    configs:
    - prefix: /svc
      retries:
        budget:
          minRetriesPerSec: 5
          percentCanRetry: 0.5
          ttlSecs: 15
        backoff:
          kind: jittered
          minMs: 10
          maxMs: 10000
    - prefix: /svc/foo
      totalTimeoutMs: 500
    - prefix: /svc/bar
      totalTimeoutMs: 200

```

This service configuration allows you to specify [service parameters](#service-parameters)
which will be applied to all services that match a specified prefix.  The service
configuration must contain a property called `configs` which contains a list
of config objects.  Each config object must specify a prefix and the
[service parameters](#service-parameters) to apply to services that match that prefix.
If a service matches more than one prefix, all parameters from the matching
configs will be applied, with parameters defined later in the configuration file
taking precedence over those defined earlier.

### Service Parameters

<aside class="notice">
These parameters are available to the service regardless of protocol. Services may also have protocol-specific parameters.
</aside>

Key                 | Default Value                 | Description
------------------- | ----------------------------- | -----------
retries             | see [retries](#retries)       | A [retry policy](#retries) for application-level retries.
totalTimeoutMs      | no timeout                    | The timeout for an entire request, including all retries, in milliseconds.
responseClassifier  | `io.l5d.http.nonRetryable5XX` | A (sometimes protocol-specific) [response classifier](#http-response-classifiers) that determines which responses should be considered failures and, of those, which should be considered [retryable](#retries).

## Client Configuration

This section defines how the clients that Linkerd creates will be configured.  The structure of this section depends on
its `kind`.

Key  | Default Value    | Description
---- | ---------------- | --------------
kind | `io.l5d.global` | Either [io.l5d.global](#global-client-config) or [io.l5d.static](#static-client-config)

### Global Client Config

```yaml
- protocol: http
  client:
    kind: io.l5d.global
    loadBalancer:
      kind: ewma
    failureAccrual:
      kind: io.l5d.consecutiveFailures
      failures: 10
```

This client configuration allows you to specify [client parameters](#client-parameters)
which will be applied to all clients.

### Static Client Config

```yaml
- protocol: http
  client:
    kind: io.l5d.static
    configs:
    - prefix: /#/io.l5d.fs
      loadBalancer:
        kind: ewma
    - prefix: /#/io.l5d.fs/{service}
      tls:
        commonName: "{service}.linkerd.io"
    - prefix: /$/inet/*/80
      failureAccrual:
        kind: io.l5d.consecutiveFailures
        failures: 10
```

This client configuration allows you to specify [client parameters](#client-parameters)
which will be applied to all clients that match a specified prefix.  The client
configuration must contain a property called `configs` which contains a list
of config objects.  Each config object must specify a prefix and the
[client parameters](#client-parameters) to apply to clients that match that prefix.
A prefix may contain wildcards (`*`) and capture variables (`{foo}`) which can
be referenced in some client parameters.
If a client matches more than one config's prefix, all parameters from the
matching configs will be applied, with parameters defined later in the
configuration file taking precedence over those defined earlier.

Note: Capture variables use greedy pattern matching.  For example (`{foo}{bar}`) is
ambiguous.  The capture variable (`{foo}`) would capture the whole segement and
(`{bar}`) would empty.  Similary, the pattern (`{foo}-{bar}`) on the segement
`a-b-c` would capture `a-b` into (`{foo}`) and `c` in (`{bar}`).

### Client Parameters

<aside class="notice">
These parameters are available to the client regardless of protocol. Clients may also have protocol-specific parameters.
</aside>

```yaml
client:
  tls:
    kind: io.l5d.noValidation
    commonName: foo
    caCertPath: /foo/caCert.pem
  requestAttemptTimeoutMs: 100
  loadBalancer:
    kind: ewma
    enableProbation: false
  requeueBudget:
    percentCanRetry: 0.25
  failureAccrual:
    kind: io.l5d.consecutiveFailures
    failures: 10
```

Key | Default Value | Description
--- | ------------- | -----------
hostConnectionPool | An empty object | see [hostConnectionPool](#host-connection-pool).
tls | no tls | The router will make requests using TLS if this parameter is provided.  It must be a [client TLS](#client-tls) object.
loadBalancer | [p2c](#power-of-two-choices-least-loaded) | A [load balancer](#load-balancer) object.
failFast | `false` | If `true`, connection failures are punished more aggressively. Should not be used with small destination pools.
requeueBudget | see [retry budget](#retry-budget-parameters) | A [requeue budget](#retry-budget-parameters) for connection-level retries.
failureAccrual | 5 consecutive failures | a [failure accrual policy](#failure-accrual) for all clients created by this router.
requestAttemptTimeoutMs | no timeout | The timeout, in milliseconds, for each attempt (original or retry) of the request made by this client.
clientSession | An empty object | see [clientSession](#client-session)

#### Host Connection Pool

This section defines the behavior of [watermark connection pools](https://twitter.github.io/finagle/docs/com/twitter/finagle/client/DefaultPool) on which most of the protocols are relying.
Note that Http2 protocol uses [SingletonPool](https://github.com/twitter/finagle/blob/master/finagle-core/src/main/scala/com/twitter/finagle/pool/SingletonPool.scala) that maintains a single connection per endpoint and will not be affected by the settings in this section. 

```yaml
client:
  hostConnectionPool:
    minSize: 0
    maxSize: 1000
    idleTimeMs: 10000
    maxWaiters: 5000
```

Key | Default Value | Description
--- | ------------- | -----------
minSize | `0` | The minimum number of connections to maintain to each host.
maxSize | Int.MaxValue | The maximum number of connections to maintain to each host.
idleTimeMs | forever | The amount of idle time for which a connection is cached in milliseconds. Only applied to connections that number greater than minSize, but fewer than maxSize.
maxWaiters | Int.MaxValue | The maximum number of connection requests that are queued when the connection concurrency exceeds maxSize.

#### Client Session

Configures the behavior of established client sessions.

Key | Default Value | Description
--- | ------------- | -----------
idleTimeMs | forever | The max amount of time for which a connection is allowed to be idle. When this time exceeded the connection will close itself.
lifeTimeMs | forever | Max lifetime of a connection.
