# HTTP/2 protocol

<!-- examples -->

> Below: Authority (Host) based routing for HTTP/2 over TLS

```yaml
routers:
- protocol: h2
  h2AccessLog: access.log
  h2AccessLogRollPolicy: daily
  h2AccessLogAppend: true
  h2AccessLogRotateCount: -1
  servers:
  - port: 4143
    tls:
      certPath: .../public/linkerd.pem
      keyPath: .../private/linkerd.pem
      caCertPath: .../ca.pem
  identifier:
    kind: io.l5d.header.token
    header: ":authority"
  dtab: |
    /svc => /#/io.l5d.fs ;
  client:
    prefix: "/#/io.l5d.fs/{service}"
    tls:
      kind: io.l5d.boundPath
      caCertPath: .../ca.pem
      commonName: "{service}"
```

> Below: plaintext gRPC

```yaml
routers:
- protocol: h2
  label: grpc
  servers:
  - port: 4142
  identifier:
    kind: io.l5d.header.path
    segments: 2
  dtab: |
    /svc => /#/io.l5d.fs ;
```
> because gRPC encodes URLs as /_serviceName_/_methodName_, we can
simply register service names into a discovery system and route
accordingly. Note that gRPC may be configured over TLS as well.

<!-- config reference -->

protocol: `h2`

The HTTP/2 protocol is used when the *protocol* option of the
[routers configuration block](#router-parameters) is set to *h2*.
This protocol has additional configuration options on the *routers* block.

Key | Default Value | Description
--- | ------------- | -----------
dstPrefix | `/svc` | A path prefix used by [H2-specific identifiers](#http-2-identifiers).
h2AccessLog | none | Sets the access log path.  If not specified, no access log is written.
h2AccessLogRollPolicy | never | When to roll the logfile. Possible values: Never, Hourly, Daily, Weekly(n) (where n is a day of the week), util-style data size strings (e.g. 3.megabytes, 1.gigabyte).
h2AccessLogAppend | true | Append to an existing logfile, or truncate it?
h2AccessLogRotateCount | -1 | How many rotated logfiles to keep around, maximum. -1 means to keep them all.
identifier | The `io.l5d.header.token` identifier | An identifier or list of identifiers. See [H2-specific identifiers](#http-2-identifiers).
requestAuthorizers | none | A list of request authorizers.  See [H2-specific request authorizers](#http-2-request-authorizers).
tracePropagator | `io.l5d.default` | A trace propagator.  See [H2-specific trace propagator](#http-2-trace-propagators).


When TLS is configured, h2 routers negotiate to communicate over
HTTP/2 via ALPN.

When TLS is not configured, h2 servers accept both
[prior knowledge](https://http2.github.io/http2-spec/#known-http) and
[HTTP Upgrade](https://http2.github.io/http2-spec/#discover-http)
requests.  Plaintext clients are currently only capable of issuing
prior-knowledge requests.

## HTTP/2 Server Parameters

Key | Default Value | Description
--- | ------------- | -----------
windowUpdateRatio | `0.99` | A number between 0 and 1, exclusive, indicating the ratio at which window updates should be sent. With a value of 0.75, updates will be sent when the available window size is 75% of its capacity.
headerTableBytes | none | Configures `SETTINGS_HEADER_TABLE_SIZE` on new streams.
initialStreamWindowBytes | 64KB | Configures `SETTINGS_INITIAL_WINDOW_SIZE` on streams.
maxConcurrentStreamsPerConnection | 1000 | Configures `SETTINGS_MAX_CONCURRENT_STREAMS` on new streams.
maxFrameBytes | 16KB | Configures `SETTINGS_MAX_FRAME_SIZE` on new streams.
maxHeaderListByts | none | Configures `SETTINGS_MAX_HEADER_LIST_SIZE` on new streams.

## HTTP/2 Service Parameters

Key                     | Default Value | Description
----------------------- | ------------- | -----------
classificationTimeoutMs | 100ms         | The amount of time to wait for a response stream to complete before determining if it should be retried.
retryBufferSize         | see below     | A RetryBufferSize object describing the size of the buffers for request and response streams used for retries.

#### RetryBufferSize

<aside class="warning">
Note that HTTP/2 clients should be careful not to send any request DATA frame whose body is longer than the current window size minus the size of the request buffer, as doing so may cause the stream to hang.
</aside>

Key           | Default Value   | Description
------------- | --------------- | -----------
requestBytes  | `16383`         | If the request stream exceeds this value, the request cannot be retried.
responseBytes | `16383`         | If the response stream exceeds this value, the request cannot be retried.

## HTTP/2 Client Parameters

Key | Default Value | Description
--- | ------------- | -----------
windowUpdateRatio: | `0.99` | A number between 0 and 1, exclusive, indicating the ratio at which window updates should be sent. With a value of 0.75, updates will be sent when the available window size is 75% of its capacity.
headerTableBytes | none | Configures `SETTINGS_HEADER_TABLE_SIZE` on new streams.
initialStreamWindowBytes | 64KB | Configures `SETTINGS_INITIAL_WINDOW_SIZE` on streams.
maxFrameBytes | 16KB | Configures `SETTINGS_MAX_FRAME_SIZE` on new streams.
maxHeaderListByts | none | Configures `SETTINGS_MAX_HEADER_LIST_SIZE` on new streams.
forwardClientCert | false | Determines if client certificates are forwarded through the `x-forwarded-client-cert` header of a request.

<aside class="notice">
`forwardClientCert` makes Linkerd forward client certificates using the `x-forwarded-client-cert` header to let destination services make authorization decisions on the requests
they receive.
</aside>

## HTTP/2 Identifiers

Identifiers are responsible for creating logical *names* from an incoming
request; these names are then matched against the dtab. (See the [Linkerd
routing overview](https://linkerd.io/in-depth/routing/) for more details on
this.) All h2 identifiers have a `kind`.  If a list of identifiers is
provided, each identifier is tried in turn until one successfully assigns a
logical *name* to the request.

Key | Default Value | Description
--- | ------------- | -----------
kind | _required_ | Either [`io.l5d.header.token`](#http-2-header-token-identifier), [`io.l5d.header.path`](#http-2-header-path-identifier), or [`io.l5d.ingress`](#http-2-ingress-identifier).

### HTTP/2 Header Token identifier

kind: `io.l5d.header.token`.

With this identifier, requests are turned into logical names using the
value of the named header. By default, the `:authority` pseudo-header
is used to provide host-based routing.

#### Namer Configuration:

> With this configuration, the value of the `my-header` header will be
used as the logical name.

```yaml
routers:
- protocol: h2
  identifier:
    kind: io.l5d.header.token
    header: my-header
  servers:
  - port: 5000
```

Key | Default Value | Description
--- | ------------- | -----------
header | `:authority` | The name of the header to extract a token from.  If there are multiple headers with this name, the last one is used.

#### Namer Path Parameters:

> Dtab Path Format

```
  / dstPrefix / headerValue
```

Key | Default Value | Description
--- | ------------- | -----------
dstPrefix | `/svc` | The `dstPrefix` as set in the routers block.
headerValue | N/A | The value of the header.

### HTTP/2 Header Path Identifier

kind: `io.l5d.header.path`

With this identifier, requests are identified using a path read from a
header. This is useful for routing gRPC requests. By default, the `:path`
pseudo-header is used.

#### Namer Configuration:

> With this configuration, a request to
`:5000/true/love/waits.php?thing=1` will be mapped to `/svc/true/love`
and will be routed based on this name by the corresponding Dtab.

```yaml
routers:
- protocol: h2
  identifier:
    kind: io.l5d.header.path
    segments: 2
  servers:
  - port: 5000
```

Key | Default Value | Description
--- | ------------- | -----------
header | `:path` | The name of the header to extract a Path from.  If there are multiple headers with this name, the last one is used.
segments | None | If specified, the number of path segments that are required extracted from each request.


#### Namer Path Parameters:

> Dtab Path Format

```
  / dstPrefix / *urlPath
```

Key | Default Value | Description
--- | ------------- | -----------
dstPrefix | `/svc` | The `dstPrefix` as set in the routers block.
urlPath | N/A | The first `segments` elements of the path from the URL

### HTTP/2 Ingress Identifier

kind: `io.l5d.ingress`

Using this identifier enables Linkerd to function as a Kubernetes ingress
controller. The ingress identifier compares HTTP/2 requests to [ingress
resource](https://kubernetes.io/docs/user-guide/ingress/) rules, and assigns a
name based on those rules.

<aside class="notice">
The HTTP/2 Ingress Identifier compares an ingress rule's `host` field to the
`:authority` header, instead of the `host` header.
</aside>

#### Identifier Configuration:

> This example watches all ingress resources in the default namespace:

```yaml
routers:
- protocol: h2
  identifier:
    kind: io.l5d.ingress
    namespace: default
  servers:
  - port: 4140
  dtab: /svc => /#/io.l5d.k8s

namers:
- kind: io.l5d.k8s
```

> An example ingress resource watched by the Linkerd ingress controller:

```yaml
apiVersion: extensions/v1beta1
kind: Ingress
metadata:
  name: my-first-ingress
  namespace: default
annotations:
  kubernetes.io/ingress.class: "linkerd"
spec:
  rules:
  - http:
      paths:
      - path: /testpath
        backend:
          serviceName: test
          servicePort: 80
```

> So an HTTP/2 request like `https://localhost:4140/testpath` would have an identified name of `/svc/default/80/test`

Key  | Default Value | Description
---- | ------------- | -----------
namespace | (all) | The Kubernetes namespace where the ingress resources are deployed. If not specified, Linkerd will watch all namespaces.
ingressClassAnnotation | `linkerd` | When using [multiple ingress controllers](https://github.com/kubernetes/ingress/blob/master/docs/faq/README.md#how-do-i-run-multiple-ingress-controllers-in-the-same-cluster), Linkerd will only use the ingress resource annotated with this class.
ignoreDefaultBackends | `false` | Identify requests only when they match an explicit ingress rule specifying a host and/or path.
host | `localhost` | The Kubernetes master host.
port | `8001` | The Kubernetes master port.

#### Identifier Path Parameters

> Dtab Path Format

```
  / dstPrefix / namespace / port / service
```

Key | Default Value | Description
--- | ------------- | -----------
dstPrefix | `/svc` | The `dstPrefix` as set in the routers block.
namespace | N/A | The Kubernetes namespace.
port | N/A | The port name.
svc | N/A | The name of the service.

### HTTP/2 Istio Identifier (Deprecated)

kind: `io.l5d.k8s.istio`

This identifier compares H2 requests to
[istio route-rules](https://istio.io/docs/concepts/traffic-management/rules-configuration.html) and assigns a name based
on those rules.

#### Identifier Configuration:

```yaml
routers:
- protocol: h2
  identifier:
    kind: io.l5d.k8s.istio
```

Key  | Default Value | Description
---- | ------------- | -----------
discoveryHost | `istio-pilot` | The host of the Istio-Pilot.
discoveryPort | 8080 | The port of the Istio-Pilot's discovery service.
apiserverHost | `istio-pilot` | The host of the Istio-Pilot.
apiserverPort | 8081 | The port of the Istio-Pilot's apiserver.

#### Identifier Path Parameters

> Dtab Path Format if the request does not point to a valid k8s cluster

```
  / dstPrefix / "ext" / host / port
```

> Dtab Path Format if the request has a valid cluster but DOES NOT match a route-rule

```
  / dstPrefix / "dest" / cluster / "::" / port
```

> Dtab Path Format if the request matches a route-rule

```
  / dstPrefix / "route" / routeRule
```


Key | Default Value | Description
--- | ------------- | -----------
dstPrefix | `/svc` | The `dstPrefix` as set in the routers block.
routeRule | N/A | The name of the route-rule that matches the incoming request.
host | N/A | The host to send the request to.
cluster | N/A | The cluster to send the request to.
port | N/A | The port to send the request to.

## HTTP/2 Request Authorizers

Request authorizers allow arbitrary rejection or modification of requests and responses. Behavior is
specific to each request authorizer. All HTTP/2 request authorizers have a `kind`. If a
list of request authorizers is provided, they each apply in the order they are defined.

Key | Default Value | Description
--- | ------------- | -----------
kind | _required_ | Only [`io.l5d.k8s.istio`](#istio-request-authorizer) is currently supported.

### HTTP/2 Istio Request Authorizer (Deprecated)

kind: `io.l5d.k8s.istio`.

With this request authorizer, all H2 requests are sent to an Istio Mixer for telemetry
recording and aggregation.

#### Request Authorizer Configuration:

> Configuration example

```yaml
requestAuthorizers:
- kind: io.l5d.k8s.istio
  mixerHost: istio-mixer
  mixerPort: 9091
```

Key | Default Value | Description
--- | ------------- | -----------
mixerHost | `istio-mixer` | Hostname of the Istio Mixer server.
mixerPort | `9091` | Port of the Mixer server.

<a name="http-2-trace-propagators"></a>
## HTTP/2 Trace Propagators

Trace propagators are responsible for propagating distributed tracing data from requests that
Linkerd receives to requests that Linkerd sends.  The trace propagator reads trace context from
a received request (usually from request headers) and stores it in a request local context.  The
trace propagator is also responsible for writing this trace context into requests that Linkerd sends
(usually into request headers).

Key | Default Value | Description
--- | ------------- | -----------
kind | _required_ | One of [`io.l5d.default`](#default-trace-propagator), [`io.l5d.zipkin`](#zipkin-trace-propagator).

<a name="default-trace-propagator"></a>
### Default Trace Propagator

kind: `io.l5d.default`.

The default trace propagator stores the trace id in the `l5d-ctx-trace` request header.  It also
reads the `l5d-sample` and, if present, uses this value as the sample rate for this request.

<aside class="notice">
The trace information in the header are serialized by Finagles `TraceId.serialize` method.
</aside>

<a name="zipkin-trace-propagator"></a>
### Zipkin Trace Propagator

kind: `io.l5d.zipkin`.

A trace propagator that writes Zipkin B3 trace headers to outgoing requests. Processes B3 Headers
received from upstream as well.

Header | Content
------ | -------
`x-b3-traceid` | 128 or 64 lower-hex encoded bits (required)
`x-b3-spanid` | 64 lower-hex encoded bits (required)
`x-b3-parentspanid` | 64 lower-hex encoded bits (absent on root span)
`x-b3-sampled` | Boolean (either “1” or “0”, can be absent)
`x-b3-flags` | '1' means debug (can be absent)

> Configuration example

```yaml
tracePropagator:
  kind: io.l5d.zipkin
```

## HTTP/2 Headers

Linkerd reads and sets several headers prefixed by `l5d-`, as is done
by the `http` protocol.

### HTTP/2 Context Headers

_Context headers_ (`l5d-ctx-*`) are generated and read by Linkerd
instances. Applications should forward all context headers in order
for all Linkerd features to work.

Header | Description
------ | -----------
`dtab-local` | Deprecated. Use `l5d-ctx-dtab` and `l5d-dtab`.
`l5d-ctx-deadline` | Describes time bounds within which a request is expected to be satisfied. Currently deadlines are only advisory and do not factor into request cancellation.
`l5d-ctx-trace` | Encodes Zipkin-style trace IDs and flags so that trace annotations emitted by Linkerd may be correlated.

<aside class="warning">
Edge services should take care to ensure these headers are not set
from untrusted sources.
</aside>

### HTTP/2 User Headers

> Append a dtab override to the dtab for this request

```shell
curl -H 'l5d-dtab: /host/web => /host/web-v2' "localhost:5000"
```

_User headers_ enable user-overrides.

Header | Description
------ | -----------
`l5d-dtab` | A client-specified delegation override.
`l5d-sample` | A client-specified trace sample rate override.

<aside class="notice">
If Linkerd processes incoming requests for applications
(i.e. in linker-to-linker configurations), applications do not need to
provide special treatment for these headers since Linkerd does <b>not</b>
forward these headers (and instead translates them into context
headers). If applications receive traffic directly, they <b>should</b>
forward these headers.
</aside>

<aside class="warning">
Edge services should take care to ensure these headers are not set
from untrusted sources.
</aside>

### HTTP/2 Informational Request Headers

The informational headers Linkerd emits on outgoing requests.

Header | Description
------ | -----------
`l5d-dst-service` | The logical service name of the request as identified by Linkerd.
`l5d-dst-client` | The concrete client name after delegation.
`l5d-dst-residual` | An optional residual path remaining after delegation.
`l5d-reqid` | A token that may be used to correlate requests in a callgraph across services and Linkerd instances.

Applications are not required to forward these headers on downstream
requests.

<aside class="notice">
The value of the dst headers may include service discovery
information including host names.  Operators may opt to remove these
headers from requests sent to the outside world.
</aside>

### HTTP/2 Informational Response Headers

The informational headers Linkerd emits on outgoing responses.

Header | Description
------ | -----------
`l5d-err` | Indicates a Linkerd-generated error. Error responses that do not have this header are application errors.

Applications are not required to forward these headers on upstream
responses.

<aside class="notice">
The value of this header may include service discovery information
including host names. Operators may opt to remove this header from
responses sent to the outside world.
</aside>
