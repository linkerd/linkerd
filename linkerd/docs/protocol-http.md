# HTTP/1.1 protocol

> Below: http-specific configuration options

```yaml
routers:
- protocol: http
  httpAccessLog: access.log
  identifier:
    kind: io.l5d.methodAndHost
  maxChunkKB: 8
  maxHeadersKB: 8
  maxInitialLineKB: 4
  maxRequestKB: 5120
  maxResponseKB: 5120
  servers:
  - port: 5000
    addForwardedHeader:
      by: {kind: "ip:port"}
      for: {kind: ip}
```

protocol: `http`

The HTTP/1.1 protocol is used when the *protocol* option of the
[routers configuration block](#router-parameters) is set to *http*.
This protocol has additional configuration options on the *routers* block.

Key | Default Value | Description
--- | ------------- | -----------
dstPrefix | `/svc` | A path prefix used by [Http-specific identifiers](#http-1-1-identifiers).
httpAccessLog | none | Sets the access log path.  If not specified, no access log is written.
identifier | The `io.l5d.header.token` identifier | An identifier or list of identifiers.  See [Http-specific identifiers](#http-1-1-identifiers).
loggers | A list of loggers.  See [Http-specific loggers](#http-1-1-loggers).
maxChunkKB | 8 | The maximum size of an HTTP chunk.
maxHeadersKB | 8 | The maximum size of all headers in an HTTP message.
maxInitialLineKB | 4 | The maximum size of an initial HTTP message line.
maxRequestKB | 5120 | The maximum size of a non-chunked HTTP request payload.
maxResponseKB | 5120 | The maximum size of a non-chunked HTTP response payload.
compressionLevel | `-1`, automatically compresses textual content types with compression level 6 | The compression level to use (on 0-9).
streamingEnabled | `true` | Streaming allows linkerd to work with HTTP messages that have large (or infinite) content bodies using chunked encoding.  Disabling this is highly discouraged.

<aside class="warning">
These memory constraints are selected to allow reliable
concurrent usage of linkerd. Changing these parameters may
significantly alter linkerd's performance characteristics.
</aside>


<a name="http-1-1-server"></a>
## HTTP Servers ##

HTTP servers accept additional configuration parameters.

> Example: default

```yaml
addForwardedHeader: {}
```

Key | Default Value | Description
--- | ------------- | -----------
addForwardedHeader | null | If set, a `Forwarded` header is added to all requests.  See [below](#http-1-1-forwarded).

<a name="http-1-1-forwarded"></a>
### Adding the `Forwarded` header ###

[RFC 7239](https://tools.ietf.org/html/rfc7239) describes how a
`Forwarded` header may be added to requests by proxies. This RFC
requests that this header not be added unless explicitly configured
and that proxies obfuscate IP addresses unless explicitly configured
to transmit them.

Key | Default Value | Description
--- | ------------- | -----------
by  | `{kind: requestRandom}` | The [labeler](#http-1-1-forwarded-labeler) to use with the router's server address
for | `{kind: requestRandom}` | The [labeler](#http-1-1-forwarded-labeler) to use with the upstream client's address

<a name="http-1-1-forwarded-labelers"></a>
#### Endpoint labelers ####

The `Forwarded` header includes labels describing the endpoints of the
upstream connection. Because this is sensitive information, it is
typically randomized.

```yaml
addForwardedHeader:
  for: {kind: ip}
  by:
    kind: static
    label: linkerd
```

Kind | Description
---- | -----------
ip | A textual IP address like `192.168.1.1` or `"[2001:db8:cafe::17]"`.
ip:port | A textual IP:PORT address like `"192.168.1.1:80"` or `"[2001:db8:cafe::17]:80"`.
connectionRandom | An obfuscated random label like `_6Oq8jJ` _generated for all requests on a connection_.
requestRandom | An obfuscated random label like `_6Oq8jJ` _generated for each request_.
router | Uses the router's `label` as an obfuscated static label.
static | Accepts a `label` parameter. Produces obfuscated static labels like `_linkerd`.

<a name="http-1-1-identifiers"></a>
## HTTP/1.1 Identifiers

Identifiers are responsible for creating logical *names* from an incoming
request; these names are then matched against the dtab. (See the [linkerd
routing overview](https://linkerd.io/doc/latest/routing/) for more details on
this.) All HTTP/1.1 identifiers have a `kind`.  If a list of identifiers is
provided, each identifier is tried in turn until one successfully assigns a
logical *name* to the request.

If no identifier is specified the `io.l5d.header.token` identifier is used.

Key | Default Value | Description
--- | ------------- | -----------
kind | _required_ | Either [`io.l5d.methodAndHost`](#method-and-host-identifier), [`io.l5d.path`](#path-identifier), [`io.l5d.header`](#header-identifier), [`io.l5d.header.token`](#header-token-identifier), or [`io.l5d.static`](#static-identifier).

<a name="method-and-host-identifier"></a>
### Method and Host Identifier

kind: `io.l5d.methodAndHost`.

With this identifier, HTTP requests are turned into logical names using a
combination of `Host` header, method, and (optionally) URI. `Host`
header value is lower-cased as per `RFC 2616`.

#### Identifier Configuration:

> Configuration example

```yaml
identifier:
  kind: io.l5d.methodAndHost
  httpUriInDst: true
```

Key | Default Value | Description
--- | ------------- | -----------
httpUriInDst | `false` | If `true` http paths are appended to destinations. This allows a form of path-prefix routing. This option is **not** recommended as performance implications may be severe; Use the [path identifier](#path-identifier) instead.


#### Identifier Path Parameters:

> Dtab Path Format for HTTP/1.1

```
  / dstPrefix / "1.1" / method / host [/ uri* ]
```

> Dtab Path Format for HTTP/1.0

```
  / dstPrefix / "1.0" / method [/ uri* ]
```

Key | Default Value | Description
--- | ------------- | -----------
dstPrefix | `/svc` | The `dstPrefix` as set in the routers block.
method | N/A | The HTTP method of the current request, ie `OPTIONS`, `GET`, `HEAD`, `POST`, `PUT`, `DELETE`, `TRACE`, or `CONNECT`.
host | N/A | The value of the current request's Host header. [Case sensitive!](https://github.com/linkerd/linkerd/issues/106). Not used in HTTP/1.0.
uri | Not used | Only considered a part of the logical name if the config option `httpUriInDst` is `true`.

<a name="path-identifier"></a>
### Path Identifier

kind: `io.l5d.path`

With this identifier, HTTP requests are turned into names based only on the
path component of the URL, using a configurable number of "/" separated
segments from the start of their HTTP path.

#### Identifier Configuration:

> With this configuration, a request to `:5000/true/love/waits.php` will be
mapped to `/svc/true/love` and will be routed based on this name by the
corresponding dtab. Additionally, because `consume` is true, after routing,
requests will be proxied to the destination service with `/waits.php` as the
path component of the URL.

```yaml
routers:
- protocol: http
  identifier:
    kind: io.l5d.path
    segments: 2
    consume: true
  servers:
  - port: 5000
```

Key | Default Value | Description
--- | ------------- | -----------
segments | `1` | Number of segments from the path that are appended to destinations.
consume | `false` | Whether to additionally strip the consumed segments from the HTTP request proxied to the final destination service. This only affects the request sent to the destination service; it does not affect identification or routing.

#### Identifier Path Parameters:

> Dtab Path Format

```
  / dstPrefix [/ *urlPath ]
```

Key | Default Value | Description
--- | ------------- | -----------
dstPrefix | `/svc` | The `dstPrefix` as set in the routers block.
urlPath | N/A | A path from the URL whose number of segments is set in the identifier block.

<a name="header-identifier"></a>
### Header Identifier

kind: `io.l5d.header`

With this identifier, HTTP requests are turned into names based only on the
value of an HTTP header.  The value of the HTTP header is interpreted as a path
and therefore must start with a `/`.

#### Identifier Configuration:

> With this configuration, the value of the `my-header` HTTP header will be used
as the logical name.

```yaml
routers:
- protocol: http
  identifier:
    kind: io.l5d.header
    header: my-header
  servers:
  - port: 5000
```

Key | Default Value | Description
--- | ------------- | -----------
header | `l5d-name` | The name of the HTTP header to use

#### Identifier Path Parameters:

> Dtab Path Format

```
  / dstPrefix [*headerValue ]
```

Key | Default Value | Description
--- | ------------- | -----------
dstPrefix | `/svc` | The `dstPrefix` as set in the routers block.
headerValue | N/A | The value of the HTTP header as a path.

<a name="header-token-identifier"></a>
### Header Token Identifier

kind: `io.l5d.header.token`

With this identifier, HTTP requests are turned into names based only on the
value of an HTTP header.  The name is a path with one segment and the value of
that segment is taken from the HTTP header.

#### Identifier Configuration:

> With this configuration, the value of the `my-header` HTTP header will be used
as the logical name.

```yaml
routers:
- protocol: http
  identifier:
    kind: io.l5d.header.token
    header: my-header
  servers:
  - port: 5000
```

Key | Default Value | Description
--- | ------------- | -----------
header | `Host` | The name of the HTTP header to use

#### Identifier Path Parameters:

> Dtab Path Format

```
  / dstPrefix / [headerValue]
```

Key | Default Value | Description
--- | ------------- | -----------
dstPrefix | `/svc` | The `dstPrefix` as set in the routers block.
headerValue | N/A | The value of the HTTP header as a path segment.

<a name="ingress-identifier"></a>
### Ingress Identifier

kind: `io.l5d.ingress`

Using this identifier enables linkerd to function as a Kubernetes ingress
controller. The ingress identifier compares HTTP requests to [ingress
resource](https://kubernetes.io/docs/user-guide/ingress/) rules, and assigns a
name based on those rules.

#### Identifier Configuration:

> This example watches all ingress resources in the default namespace:

```yaml
routers:
- protocol: http
  identifier:
    kind: io.l5d.ingress
    namespace: default
  servers:
  - port: 4140
  dtab: /svc => /#/io.l5d.k8s

namers:
- kind: io.l5d.k8s
```

> An example ingress resource watched by the linkerd ingress controller:

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

> So an HTTP request like `http://localhost:4140/testpath` would have an identified name of `/svc/default/80/test`

Key  | Default Value | Description
---- | ------------- | -----------
namespace | (all) | The Kubernetes namespace where the ingress resources are deployed. If not specified, linkerd will watch all namespaces.
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


<a href="istio-identifier"></a>
### Istio Identifier

kind: `io.l5d.k8s.istio`

This identifier compares HTTP requests to
[istio route-rules](https://istio.io/docs/concepts/traffic-management/rules-configuration.html) and assigns a name based
on those rules.

 #### Identifier Configuration:

```yaml
routers:
- protocol: http
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

<a name="static-identifier"></a>
### Static Identifier

kind: `io.l5d.static`

This identifier always assigns the same static name to all requests.

#### Identifier Configuration:

```yaml
routers:
- protocol: http
  identifier:
    kind: io.l5d.static
    path: /foo/bar
```

Key  | Default Value | Description
---- | ------------- | -----------
path | _required_    | The name to assign to all requests

#### Identifier Path Parameters

> Dtab Path Format

```
  / dstPrefix / *path
```

Key | Default Value | Description
--- | ------------- | -----------
dstPrefix | `/svc` | The `dstPrefix` as set in the routers block.
path | N/A | The path given in the configuration.

<a name="http-1-1-loggers"></a>
## HTTP/1.1 Loggers

Loggers allow recording of arbitrary information about requests. Destination of
information is specific to each logger. All HTTP/1.1 loggers have a `kind`. If a
list of loggers is provided, they each log in the order they are defined.

Key | Default Value | Description
--- | ------------- | -----------
kind | _required_ | Only [`io.l5d.k8s.istio`](#istio-logger) is currently supported.

<a name="istio-logger"></a>
### Istio Logger

kind: `io.l5d.k8s.istio`.

With this logger, all HTTP requests are sent to an Istio Mixer for telemetry
recording and aggregation.

#### Logger Configuration:

> Configuration example

```yaml
loggers:
- kind: io.l5d.k8s.istio
  mixerHost: istio-mixer
  mixerPort: 9091
```

Key | Default Value | Description
--- | ------------- | -----------
mixerHost | `istio-mixer` | Hostname of the Istio Mixer server.
mixerPort | `9091` | Port of the Mixer server.

<a name="http-engines"></a>
## HTTP Engines

> This configures an HTTP router that uses the new
netty4 implementation on both the client and server:

```yaml
- protocol: http
  servers:
  - port: 4141
    ip: 0.0.0.0
    engine:
      kind: netty4
  client:
    engine:
      kind: netty4
```

An _engine_ may be configured on HTTP clients and servers, causing an
alternate HTTP implementation to be used.

Key | Default Value | Description
--- | ------------- | -----------
kind | `netty4` | Either `netty3` or `netty4`

<a name="http-headers"></a>
## HTTP Headers

linkerd reads and sets several headers prefixed by `l5d-`.

<a name="context-headers"></a>
### Context Headers

_Context headers_ (`l5d-ctx-*`) are generated and read by linkerd
instances. Applications should forward all context headers in order
for all linkerd features to work.

Header | Description
------ | -----------
`dtab-local` | Deprecated. Use `l5d-ctx-dtab` and `l5d-dtab`.
`l5d-ctx-deadline` | Describes time bounds within which a request is expected to be satisfied. Currently deadlines are only advisory and do not factor into request cancellation.
`l5d-ctx-trace` | Encodes Zipkin-style trace IDs and flags so that trace annotations emitted by linkerd may be correlated.

<aside class="warning">
Edge services should take care to ensure these headers are not set
from untrusted sources.
</aside>

### User Headers

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
If linkerd processes incoming requests for applications
(i.e. in linker-to-linker configurations), applications do not need to
provide special treatment for these headers since linkerd does <b>not</b>
forward these headers (and instead translates them into context
headers). If applications receive traffic directly, they <b>should</b>
forward these headers.
</aside>

<aside class="warning">
Edge services should take care to ensure these headers are not set
from untrusted sources.
</aside>

### Informational Request Headers

The informational headers linkerd emits on outgoing requests.

Header | Description
------ | -----------
`l5d-dst-service` | The logical service name of the request as identified by linkerd.
`l5d-dst-client` | The concrete client name after delegation.
`l5d-dst-residual` | An optional residual path remaining after delegation.
`l5d-reqid` | A token that may be used to correlate requests in a callgraph across services and linkerd instances.

Applications are not required to forward these headers on downstream
requests.

<aside class="notice">
The value of the dst headers may include service discovery
information including host names.  Operators may opt to remove these
headers from requests sent to the outside world.
</aside>

### Informational Response Headers

The informational headers linkerd emits on outgoing responses.

Header          | Description
--------------- | -----------
`l5d-err`       | Indicates a linkerd-generated error. Error responses that do not have this header are application errors.
`l5d-retryable` | Indicates that the request for this response is known to be safe to retry (for example, because it was not delivered to its destination).

Applications should not forward these headers on upstream
responses.

<aside class="notice">
The value of this header may include service discovery information
including host names. Operators may opt to remove this header from
responses sent to the outside world.
</aside>
