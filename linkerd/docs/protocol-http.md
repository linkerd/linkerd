# HTTP/1.1 protocol

> Below: http-specific configuration options

```yaml
routers:
- protocol: http
  httpAccessLog: access.log
  identifier:
    kind: io.l5d.methodAndHost
  maxChunkKB: 8KB
  maxHeadersKB: 8KB
  maxInitialLineKB: 4KB
  maxRequestKB: 5MB
  maxResponseKB: 5MB
  servers:
  - port: 5000
```

> Below: an example HTTP router config that routes all `POST` requests to 8091
and all other requests to 8081,
using the default identifier of `io.l5d.methodAndHost`,
listening on port 5000

```yaml
routers:
- protocol: http
  label: split-get-and-post
  baseDtab: |
    /method/*    => /$/inet/127.1/8081;
    /method/POST => /$/inet/127.1/8091;
    /http/1.1    => /method;
  servers:
  - port: 5000
```
> The baseDtab above is written to work with the
[`methodAndHost` identifier](#method-and-host-identifier).
Using a different identifier would require a different set of dtab rules.

protocol: `http`

The HTTP/1.1 protocol is used when the *protocol* option of the
[routers configuration block](#router-parameters) is set to *http*.
This protocol has additional configuration options on the *routers* block.

Key | Default Value | Description
--- | ------------- | -----------
dstPrefix | `http` | A path prefix used by [Http-specific identifiers](#http-1-1-identifiers).
httpAccessLog | none | Sets the access log path.  If not specified, no access log is written.
identifier | The `methodAndHost` identifier | An identifier or list of identifiers.  See [Http-specific identifiers](#http-1-1-identifiers).
maxChunkKB | 8KB | The maximum size of an HTTP chunk.
maxHeadersKB | 8KB | The maximum size of all headers in an HTTP message.
maxInitialLineKB | 4KB | The maximum size of an initial HTTP message line.
maxRequestKB | 5MB | The maximum size of a non-chunked HTTP request payload.
maxResponseKB | 5MB | The maximum size of a non-chunked HTTP response payload.
compressionLevel | `-1`, automatically compresses textual content types with compression level 6 | The compression level to use (on 0-9).

<aside class="warning">
These memory constraints are selected to allow reliable
concurrent usage of linkerd. Changing these parameters may
significantly alter linkerd's performance characteristics.
</aside>

<a name="http-1-1-identifiers"></a>
## HTTP/1.1 Identifiers

Identifiers are responsible for creating logical *names* from an incoming
request; these names are then matched against the dtab. (See the [linkerd
routing overview](https://linkerd.io/doc/latest/routing/) for more details on
this.) All HTTP/1.1 identifiers have a `kind`.  If a list of identifiers is
provided, each identifier is tried in turn until one successfully assigns a
logical *name* to the request.

Key | Default Value | Description
--- | ------------- | -----------
kind | _required_ | Either [`io.l5d.methodAndHost`](#method-and-host-identifier) or [`io.l5d.path`](#path-identifier).

<a name="method-and-host-identifier"></a>
### Method and Host Identifier

kind: `io.l5d.methodAndHost`.

With this identifier, HTTP requests are turned into logical names using a
combination of Host header, method, and (optionally) URI.

#### Namer Configuration:

> Configuration example

```yaml
identifier:
  kind: io.l5d.methodAndHost
  httpUriInDst: true
```

Key | Default Value | Description
--- | ------------- | -----------
httpUriInDst | `false` | If `true` http paths are appended to destinations. This allows a form of path-prefix routing. This option is **not** recommended as performance implications may be severe; Use the [path identifier](#path-identifier) instead.


#### Namer Path Parameters:

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
dstPrefix | `http` | The `dstPrefix` as set in the routers block.
method | N/A | The HTTP method of the current request, ie `OPTIONS`, `GET`, `HEAD`, `POST`, `PUT`, `DELETE`, `TRACE`, or `CONNECT`.
host | N/A | The value of the current request's Host header. [Case sensitive!](https://github.com/BuoyantIO/linkerd/issues/106). Not used in HTTP/1.0.
uri | Not used | Only considered a part of the logical name if the config option `httpUriInDst` is `true`.

<a name="path-identifier"></a>
### Path Identifier

kind: `io.l5d.path`

With this identifier, HTTP requests are turned into names based only on the
path component of the URL, using a configurable number of "/" separated
segments from the start of their HTTP path.

#### Namer Configuration:

> With this configuration, a request to `:5000/true/love/waits.php` will be
mapped to `/http/true/love` and will be routed based on this name by the
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

#### Namer Path Parameters:

> Dtab Path Format

```
  / dstPrefix [/ *urlPath ]
```

Key | Default Value | Description
--- | ------------- | -----------
dstPrefix | `http` | The `dstPrefix` as set in the routers block.
urlPath | N/A | A path from the URL whose number of segments is set in the identifier block.

<a name="header-identifier"></a>
### Header Identifier

kind: `io.l5d.header`

With this identifier, HTTP requests are turned into names based only on the
value of an HTTP header.  If the header value is a valid path, that path is
used.  Otherwise, the header value is converted to a path with one path segment.

#### Namer Configuration:

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
header | `l5d-dst-concrete` | The name of the HTTP header to use

#### Namer Path Parameters:

> Dtab Path Format

```
  / dstPrefix [/ *headerValue ]
```

Key | Default Value | Description
--- | ------------- | -----------
dstPrefix | `http` | The `dstPrefix` as set in the routers block.
headerValue | N/A | The value of the HTTP header as a path, if it is a valid path.  The value of the HTTP header as a single path segment, otherwise.

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
kind | `netty3` | Either `netty3` or `netty4` (`netty4` will become default in an upcoming release).

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

> Append a dtab override to the baseDtab for this request

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
