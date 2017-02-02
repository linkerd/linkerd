<a name="routers"></a>
# Routers

All configurations must define a **routers** key, the value of which
must be an array of router configurations. Routers also include **servers**, which define their entry points, and **client**, which configures how clients are built.

## Router Parameters

<aside class="notice">
These parameters are available to the router regardless of protocol. Routers may also have protocol-specific parameters.
</aside>

```yaml
routers:
- protocol: http
  servers: ...
  client: ...
  announcers: ...
  bindingCache: ...
  label: myPackIce
  dstPrefix: /walruses/http
  dtab: |
    /host                => /#/io.l5d.fs;
    /walruses/http/1.1/* => /host;
  failFast: false
  originator: true
  timeoutMs: 10000
  bindingTimeoutMs: 5000
  responseClassifier:
    kind: io.l5d.nonRetryable5XX
```

Key | Default Value | Description
--- | ------------- | -----------
protocol | _required_ | Either [`http`](#http-1-1-protocol), [`h2`](#http-2-protocol), [`thrift`](#thrift-protocol), or [`mux`](#mux-protocol-experimental).
servers | _required_ | A list of [server objects](#servers).
announcers | an empty list | A list of service discovery [announcers](#announcers) that servers can announce to.
dtab | an empty dtab | Sets the base delegation table. See [dtabs](https://linkerd.io/doc/dtabs/) for more.
bindingTimeoutMs | 10 seconds | The maximum amount of time in milliseconds to spend binding a path.
bindingCache | see [binding cache](#binding-cache) | Binding cache size configuration.
client | an empty object | An object of [client params](#client-parameters).
dstPrefix | protocol dependent | A path prefix to be used on request destinations.
failFast | `false` | If `true`, connection failures are punished more aggressively. Should not be used with small destination pools.
originator | `false` | If `true`, indicates that this router is the first hop for linker-to-linker requests, and reflects that in the router's stats. Useful for deduping linker-to-linker stats.
interpreter | default interpreter | An [interpreter object](#interpreter) determining what module will be used to process destinations.
label | the value of *protocol* | The name of the router (in stats and the admin ui)
response Classifier | `io.l5d.nonRetryable5XX` | A (sometimes protocol-specific) [response classifier](#http-response-classifiers) that determines which responses should be considered failures and, of those, which should be considered [retryable](#retries).
timeoutMs | no timeout | Per-request timeout in milliseconds.

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
```

Key | Default Value | Description
-------------- | -------------- | --------------
paths | `100` | Max number of paths in the path cache.
trees | `100` | Max number of trees in the tree cache.
bounds | `100` | Max number of bounds in the bounds cache.
clients | `10` | Max number of clients in the clients cache.

<a name="server-parameters"></a>
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
tls | no tls | The server will serve over TLS if this parameter is provided. see [TLS](#server-tls).
maxConcurrentRequests | unlimited | The maximum number of concurrent requests the server will accept.
announce | an empty list | A list of concrete names to announce using the router's [announcers](#announcers).


<a name="client-parameters"></a>
## Client Parameters


<aside class="notice">
These parameters are available to the client regardless of protocol. Clients may also have protocol-specific parameters.
</aside>


```yaml
client:
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
  failureAccrual:
    kind: io.l5d.consecutiveFailures
    failures: 10
```

Key | Default Value | Description
--- | ------------- | -----------
hostConnectionPool | An empty object | see [hostConnectionPool](#host-connection-pool).
tls | no tls | The router will make requests using TLS if this parameter is provided.  It must be a [client TLS](#client-tls) object.
loadBalancer | [p2c](#power-of-two-choices-least-loaded) | A [load balancer](#load-balancer) object.
retries | see [retries](#retries) | A [retry policy](#retries) for all clients created by this router.
failureAccrual | 5 consecutive failures | a [failure accrual policy](#failure-accrual) for all clients created by this router.

#### Host Connection Pool

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
idleTimeMs | forever | The amount of idle time for which a connection is cached in milliseconds.
maxWaiters | Int.MaxValue | The maximum number of connection requests that are queued when the connection concurrency exceeds maxSize.
