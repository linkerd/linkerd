# HTTP/2 protocol #

<!-- examples -->

> Below: Authority (Host) based routing for HTTP/2 over TLS

```yaml
routers:
- protocol: h2
  experimental: true
  servers:
  - port: 4143
    tls:
      certPath: .../public/linkerd.pem
      keyPath: .../private/linkerd.pem
      caCertPath: .../ca.pem
  identifier:
    kind: io.l5d.headerToken
    header: ":authority"
  dtab: |
    /srv => /#/io.l5d.fs ;
    /h2 => /srv ;
  client:
    tls:
      kind: io.l5d.boundPath
      caCertPath: .../ca.pem
      names:
      - prefix: "/#/io.l5d.fs/{service}"
        commonNamePattern: "{service}"
```

> Below: plaintext gRPC

```yaml
routers:
- protocol: h2
  experimental: true
  label: grpc
  servers:
  - port: 4142
  identifier:
    kind: io.l5d.headerPath
    segments: 2
  dtab: |
    /srv => /#/io.l5d.fs ;
    /h2 => /srv ;
```
> because gRPC encodes URLs as /_serviceName_/_methodName_, we can
simply register service names into a discovery system and route
accordingly. Note that gRPC may be configured over TLS as well.

<!-- config reference -->

protocol: `h2`

linkerd now has _experimental_ support for HTTP/2. There are a number
of
[open issues](https://github.com/linkerd/linkerd/issues?q=is%3Aopen+is%3Aissue+label%3Ah2)
that are being addressed. Please
[report](https://github.com/linkerd/linkerd/issues/new) any
additional issues with this protocol!

Key | Default Value | Description
--- | ------------- | -----------
dstPrefix | `h2` | A path prefix used by [H2-specific identifiers](#h2-identifiers).
experimental | `false` | Set this to `true` to opt-in to experimental h2 support.

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
windowUpdateRatio: | `0.99` | A number between 0 and 1, exclusive, indicating the ratio at which window updates should be sent. With a value of 0.75, updates will be sent when the available window size is 75% of its capacity.
headerTableBytes | none | Configures `SETTINGS_HEADER_TABLE_SIZE` on new streams.
initialStreamWindowBytes | 64KB | Configures `SETTINGS_INITIAL_WINDOW_SIZE` on streams.
maxConcurrentStreamsPerConnection | unlimited | Configures `SETTINGS_MAX_CONCURRENT_STREAMS` on new streams.
maxFrameBytes | 16KB | Configures `SETTINGS_MAX_FRAME_SIZE` on new streams.
maxHeaderListByts | none | Configures `SETTINGS_MAX_HEADER_LIST_SIZE` on new streams.

## HTTP/2 Client Parameters

Key | Default Value | Description
--- | ------------- | -----------
windowUpdateRatio: | `0.99` | A number between 0 and 1, exclusive, indicating the ratio at which window updates should be sent. With a value of 0.75, updates will be sent when the available window size is 75% of its capacity.
headerTableBytes | none | Configures `SETTINGS_HEADER_TABLE_SIZE` on new streams.
initialStreamWindowBytes | 64KB | Configures `SETTINGS_INITIAL_WINDOW_SIZE` on streams.
maxFrameBytes | 16KB | Configures `SETTINGS_MAX_FRAME_SIZE` on new streams.
maxHeaderListByts | none | Configures `SETTINGS_MAX_HEADER_LIST_SIZE` on new streams.


<a name="h2-identifiers"></a>
## HTTP/2 Identifiers

Identifiers are responsible for creating logical *names* from an incoming
request; these names are then matched against the dtab. (See the [linkerd
routing overview](https://linkerd.io/doc/latest/routing/) for more details on
this.) All h2 identifiers have a `kind`.  If a list of identifiers is
provided, each identifier is tried in turn until one successfully assigns a
logical *name* to the request.

Key | Default Value | Description
--- | ------------- | -----------
kind | _required_ | The name of an identifier plugin, such as [`io.l5d.headerToken`](#header-token-identifier) or [`io.l5d.headerPath`](#headerpath-identifier).

<a name="header-token-identifier"></a>
### Header Token identifier

kind: `io.l5d.headerToken`.

With this identifier, requests are turned into logical names using the
value of the named header. By default, the `:authority` pseudo-header
is used to provide host-based routing.

#### Namer Configuration:

> With this configuration, the value of the `my-header` header will be
used as the logical name.

```yaml
routers:
- protocol: h2
  experimental: true
  identifier:
    kind: io.l5d.headerToken
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
dstPrefix | `http` | The `dstPrefix` as set in the routers block.
headerValue | N/A | The value of the header.

<a name="header-path-identifier"></a>
### Header Path Identifier

kind: `io.l5d.headerPath`

With this identifier, requests are identified using a path read from a
header. This is useful for routing gRPC requests. By default, the `:path`
psuedo-header is used.

#### Namer Configuration:

> With this configuration, a request to
`:5000/true/love/waits.php?thing=1` will be mapped to `/h2/true/love`
and will be routed based on this name by the corresponding Dtab.

```yaml
routers:
- protocol: h2
  experimental: true
  identifier:
    kind: io.l5d.headerPath
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
dstPrefix | `h2` | The `dstPrefix` as set in the routers block.
urlPath | N/A | The first `segments` elements of the path from the URL

<a name="h2-headers"></a>
## Headers

linkerd reads and sets several headers prefixed by `l5d-`, as is done
by the `http` protocol.

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
`l5d-dst-logical` | The logical name of the request as identified by linkerd.
`l5d-dst-concrete` | The concrete client name after delegation.
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

Header | Description
------ | -----------
`l5d-err` | Indicates a linkerd-generated error. Error responses that do not have this header are application errors.

Applications are not required to forward these headers on upstream
responses.

<aside class="notice">
The value of this header may include service discovery information
including host names. Operators may opt to remove this header from
responses sent to the outside world.
</aside>
