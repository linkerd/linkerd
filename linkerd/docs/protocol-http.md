# HTTP/1.1 protocol

*(for the [routers](config.md#routers) key)*

Router configuration options include:

* *httpAccessLog* -- Sets the access log path.  If not specified, no
access log is written.
* *identifier* -- [Http-specific identifier](#protocol-http-identifiers) (default:
io.l5d.methodAndHost)

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
## HTTP/1.1 Identifiers

Identifiers are objects responsible for creating logical names from an incoming
request with the following parameters:

* *kind* -- One of the supported identifier plugins, by fully-qualified class
 name. Current plugins include:
  * *io.l5d.methodAndHost*
  * *io.l5d.path*
* any other identifier-specific parameters

### Method and Host

`io.l5d.methodAndHost`

HTTP requests are routed by a combination of Host header, method, and URI.

* *httpUriInDst* -- If `true` http paths are appended to destinations. This
  allows path-prefix routing. (default: false)

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

### Path

`io.l5d.path`

HTTP requests are routed based on a configurable number of "/" separated
segments from the start of their HTTP path.

* *segments* -- Number of segments from the path that are appended to
  destinations. (default: 1)

For instance, here's a router configured with an http path identifier:

```yaml
routers:
- protocol: http
  dstPrefix: /custom/prefix
  identifier:
    kind: io.l5d.path
    segments: 2
  servers:
    port: 5000
```

A request to `:5000/true/love/waits.php` will be identified as
`/custom/prefix/true/love`.

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
- `l5d-ctx-trace`: encodes zipkin-style trace IDs and flags so that
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
