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
