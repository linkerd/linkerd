# HTTP/1.1 protocol

The HTTP/1.1 protocol is used when the *protocol* option of the
[routers configuration block](config.md#routers) is set to *http*.
With this protocol selected, configuration options on the *routers* block
include:

* *httpAccessLog* -- Sets the access log path.  If not specified, no
access log is written.
* *identifier* -- [Http-specific identifier](#protocol-http-identifiers) (default:
io.l5d.methodAndHost)
* *maxChunkKB* -- The maximum size of an HTTP chunk (default: 8KB)
* *maxHeadersKB* -- The maximum size of all headers in an HTTP message (default: 8KB)
* *maxInitialLineKB* -- The maximum size of an initial HTTP
  message line (default: 4KB)
* *maxRequestKB* -- The maximum size of a non-chunked HTTP request
  payload (default: 5MB)
* *maxResponseKB* -- The maximum size of a non-chunked HTTP response
  payload (default: 5MB)
* *compressionLevel* -- The compression level to use (on 0-9)
  (default: -1, automatically compresses textual content types with
  compression level 6)

_Note_: These memory constraints are selected to allow reliable
concurrent usage of linkerd. Changing these parameters may
significantly alter linkerd's performance characteristics.

<a name="protocol-http-defaults"></a>
## Example
As an example, here's an HTTP router config that routes all `POST`
requests to 8091 and all other requests to 8081, using the default
identifier of `io.l5d.methodAndHost`, listening on port 5000:

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

(Note that the dtab is written in terms of names produced by the
`methodAndHost` identifier. Using a different identifier would require a
different set of dtab rules. See the next section for more on identifiers.)

<a name="protocol-http-identifiers"></a>
## HTTP/1.1 Identifiers

Identifiers are responsible for creating logical *names* from an incoming
request; these names are then matched against the dtab. (See the [linkerd
routing overview](https://linkerd.io/doc/latest/routing/) for more details on
this.) HTTP/1.1 identifiers are configured with the following parameters:

* *kind* -- The fully-qualified class name of an identifier. Current
identifiers include:
  * *io.l5d.methodAndHost*
  * *io.l5d.path*
* other identifier-specific parameters

### The Method and Host Identifier

This identifier is selected by setting the *kind* value of the *identifier*
configuration block to `io.l5d.methodAndHost`.

With this identifier, HTTP requests are turned into logical names using a
combination of Host header, method, and (optionally) URI. Configuration
settings include:

* *httpUriInDst* -- If `true` http paths are appended to destinations. This
  allows a form of path-prefix routing. This option is **not** recommended as
  performance implications may be severe; it has been supplanted by the path
  identifier below. (default: false)

The methodAndHost identifier generates HTTP/1.1 logical names of the form:
```
  / dstPrefix / "1.1" / method / host [/ uri* ]
```
For HTTP/1.0 requests, logical names are of the form:
```
  / dstPrefix / "1.0" / method [/ uri* ]
```

In both cases, `uri` is only considered a part of the logical name if the
config option `httpUriInDst` is true.

Note that `dstPrefix`, if unset in the identifier configuration block,
defaults to "http".

### The Path Identifier

This identifier is selected by setting the *kind* value of an *identifier*
block to `io.l5d.path`.

With this identifier, HTTP requests are turned into names based only on the
path component of the URL, using a configurable number of "/" separated
segments from the start of their HTTP path. Configuration options include:

* *segments* -- Number of segments from the path that are appended to
  destinations. (default: 1)
* *consume* -- Whether to additionally strip the consumed segments from the
  HTTP request proxied to the final destination service. (default: false)

Note that *consume* only affects the request sent to the destination service;
it does not affect identification or routing.

The path identifier generates logical names of the form:
```
  / dstPrefix / [*segments* number of segments from the URL path]
```

Note that `dstPrefix`, if unset in the identifier configuration block,
defaults to "http". For example, here's a router configured with the path
identifier:

```yaml
routers:
- protocol: http
  identifier:
    kind: io.l5d.path
    segments: 2
    consume: true
  servers:
    port: 5000
```

With this configuration, a request to `:5000/true/love/waits.php` will be
mapped to `/http/true/love` and will be routed based on this name by the
corresponding dtab. Additionally, because `consume` is true, after routing,
requests will be proxied to the destination service with `/waits.php` as the
path component of the URL.

## HTTP Engines

An _engine_ may be configured on HTTP clients and servers, causing an
alternate HTTP implementation to be used. Currently there are two
supported HTTP implementations: _netty3_ (default) and _netty4_ (will
become default in an upcoming release).

For example, the following configures an HTTP router that uses the new
netty4 implementation on both the client and server:

```yaml
- protocol: http
  ...
  servers:
  - port: 4141
    ip: 0.0.0.0
    engine:
      kind: netty4
  client:
    engine:
      kind: netty4
```


## HTTP Headers

linkerd reads and sets several headers prefixed by `l5d-`.

### Context Headers

_Context headers_ (`l5d-ctx-*`) are generated and read by linkerd
instances. Applications should forward all context headers in order
for all linkerd features to work. These headers include:

- `dtab-local`: currently (until the next release of finagle), the
  `dtab-local` header is used to propagate dtab context. In an
  upcoming release this header will no longer be honored, in favor of
  `l5d-ctx-dtab` and `l5d-dtab`.
- `l5d-ctx-deadline`: describes time bounds within which a request is
  expected to be satisfied. Currently deadlines are only advisory and
  do not factor into request cancellation.
- `l5d-ctx-trace`: encodes Zipkin-style trace IDs and flags so that
  trace annotations emitted by linkerd may be correlated.

Edge services should take care to ensure these headers are not set
from untrusted sources.

### User Headers

_User headers_ are useful to allow user-overrides

- `l5d-dtab`: a client-specified delegation override
- `l5d-sample`: a client-specified trace sample rate override

Note that if linkerd processes incoming requests for applications
(i.e. in linker-to-linker configurations), applications do not need to
provide special treatment for these headers since linkerd does _not_
forward these headers (and instead translates them into context
headers). If applications receive traffic directly, they _should_
forward these headers.

Edge services should take care to ensure these headers are not set
from untrusted sources.

### Informational Request Headers

In addition to the context headers, linkerd may emit the following
headers on outgoing requests:

- `l5d-dst-logical`: the logical name of the request as identified by linkerd
- `l5d-dst-concrete`: the concrete client name after delegation
- `l5d-dst-residual`: an optional residual path remaining after delegation
- `l5d-reqid`: a token that may be used to correlate requests in a
               callgraph across services and linkerd instances

Applications are not required to forward these headers on downstream
requests.

The value of the _dst_ headers may include service discovery
information including host names.  Operators may opt to remove these
headers from requests sent to the outside world.

### Informational Response Headers

linkerd may emit the following _informational_ headers on outgoing
responses:

- `l5d-err`: indicates a linkerd-generated error. Error responses
             that do not have this header are application errors.

Applications are not required to forward these headers on upstream
responses.

The value of this header may include service discovery information
including host names. Operators may opt to remove this header from
responses sent to the outside world.
