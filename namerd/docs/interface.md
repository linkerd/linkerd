# Interfaces

An interface is a published network interface to Namerd. All of the interfaces
listed below provide Finagle's [`NameInterpreter`](
https://twitter.github.io/finagle/docs/com/twitter/finagle/naming/NameInterpreter.html)
functionality for remote resolution of destinations using dtabs stored in
Namerd. Additionally, the [`io.l5d.httpController`](#http-controller) interface
provides a dtab read/write API that's used by
[namerctl](https://github.com/linkerd/namerctl).

<aside class="notice">
These parameters are available to the interface regardless of kind. Interfaces
may also have kind-specific parameters.
</aside>

Key | Default Value | Description
--- | ------------- | -----------
kind | _required_ | Either [`io.l5d.thriftNameInterpreter`](#thrift-name-interpreter), [`io.l5d.mesh`](#grpc-mesh-interface),[`io.l5d.destination`](#linkerd2-destination-interface) or [`io.l5d.httpController`](#http-controller).
ip | interface dependent | The local IP address on which to serve the namer interface.
port | interface dependent | The port number on which to serve the namer interface.
socketOptions | none | Socket options for a configured interface. See [Socket Options](https://linkerd.io/config/head/linkerd/index.html#socket-options)
tls | no tls | The namer interface will serve over TLS if this parameter is provided. See [Server TLS](https://linkerd.io/config/head/linkerd#server-tls).

## Thrift Name Interpreter

kind: `io.l5d.thriftNameInterpreter`

A read-only interface providing `NameInterpreter` functionality over the
ThriftMux protocol. Use Linkerd's `io.l5d.namerd` interpreter to resolve
destinations via this interface.

Key | Default Value | Description
--- | ------------- | -----------
ip | loopback address | The local IP address on which to serve the namer interface. A value like 0.0.0.0 configures Namerd to listen on all local IPv4 interfaces.
port | `4100` | The port number on which to serve the namer interface.
socketOptions | none | Socket options for the thrift name interpreter interface. See [Socket Options](https://linkerd.io/config/head/linkerd/index.html#socket-options)
cache | see [cache](#cache) | Binding and address cache size configuration.
tls | no tls | The namer interface will serve over TLS if this parameter is provided. See [Server TLS](https://linkerd.io/config/head/linkerd#server-tls).

### Cache

Key | Default Value | Description
-------------- | -------------- | --------------
bindingCacheActive | `1000` | The size of the binding active cache.
bindingCacheInactive | `100` | The size of the binding inactive cache.
bindingCacheInactiveTTLSecs | `600` | The amount of time, in seconds, to keep objects in the binding inactive cache before expiring them.
addrCacheActive | `1000` | The size of the address active cache.
addrCacheInactive | `100` | The size of the address inactive cache.
addrCacheInactiveTTLSecs | `600` | The amount of time, in seconds, to keep objects in the address inactive cache before expiring them.

## gRPC Mesh Interface

kind: `io.l5d.mesh`

A read-only interface providing `NameInterpreter` functionality over the gRPC
protocol. Use Linkerd's `io.l5d.mesh` interpreter to resolve destinations via
this interface.

Key | Default Value | Description
--- | ------------- | -----------
ip | loopback address | The local IP address on which to serve the namer interface. A value like 0.0.0.0 configures Namerd to listen on all local IPv4 interfaces.
port | `4321` | The port number on which to serve the namer interface.
socketOptions | none | Socket options for the mesh interface. See [Socket Options](https://linkerd.io/config/head/linkerd/index.html#socket-options)
tls | no tls | The namer interface will serve over TLS if this parameter is provided. See [Server TLS](https://linkerd.io/config/head/linkerd#server-tls). The server TLS key file must be in PKCS#8 format.
h2AccessLog | none | Sets the access log path.  If not specified, no access log is written.
h2AccessLogRollPolicy | never | When to roll the logfile. Possible values: Never, Hourly, Daily, Weekly(n) (where n is a day of the week), util-style data size strings (e.g. 3.megabytes, 1.gigabyte).
h2AccessLogAppend | true | Append to an existing logfile, or truncate it
h2AccessLogRotateCount | -1 | How many rotated logfiles to keep around, maximum. -1 means to keep them all.

## Linkerd2 Destination Interface

kind: `io.l5d.destination`

A gRPC interface implementation based on Linkerd 2's [destination API](https://github.com/linkerd/linkerd2-proxy-api/blob/master/proto/destination.proto). It's intended for Linkerd 2
proxies to resolve request paths through Namerd's interpreter in a Linkerd 1.x Kubernetes daemonset environment.

Key | Default Value | Description
--- | ------------- | -----------
ip | loopback address | The local IP address on which to serve the destination interface. A value like 0.0.0.0 configures Namerd to listen on all local IPv4 interfaces.
port | `8086` | The port number on which to serve the destination interface.
socketOptions | none | Socket options to set for the l5d2 destination interface. See [Socket Options](https://linkerd.io/config/head/linkerd/index.html#socket-options)
prefix | /svc | The prefix to use when delegating Linkerd2 request paths to Namerd
namespace | default | The namespace with which Namerd should use to retrieve a dtab

## Http Controller

kind: `io.l5d.httpController`

The HTTP controller provides APIs for reading and writing dtabs, as well as for
viewing how names are resolved.  This API can also be accessed using the
[namerctl](https://github.com/linkerd/namerctl) command line tool.
Additionally, this API provides an HTTP implementation of the `NameInterpreter`
interface. Use Linkerd's `io.l5d.namerd.http` interpreter to resolve
destinations via this interface.

Key | Default Value | Description
--- | ------------- | -----------
ip | loopback address | The local IP address on which to serve the namer interface. A value like 0.0.0.0 configures Namerd to listen on all local IPv4 interfaces.
port | `4180` | The port number on which to serve the namer interface.
socketOptions | none | Socket options to set for the http controller interface. See [Socket Options](https://linkerd.io/config/head/linkerd/index.html#socket-options)
tls | no tls | The namer interface will serve over TLS if this parameter is provided. See [Server TLS](https://linkerd.io/config/head/linkerd#server-tls).

### GET /api/1/dtabs

> Sample request

```
curl :4180/api/1/dtabs
```

> Sample response

```
["default"]
```

Returns a list of all dtab namespaces.

Response Code | Description
------------- | -----------
200           | Ok

Content-types: application/json

### GET /api/1/dtabs/&lt;namespace&gt;

> Sample request

```
curl :4180/api/1/dtabs/default
```

> Sample response

```
[{"prefix":"/svc","dst":"/#/io.l5d.fs"}]
```

Returns the requested dtab.  The dtab version is returned in the Etag response
header.

Parameter | Type | Description
--------- | ---- | -----------
namespace | path | The dtab namespace to retrieve.
watch | uri | If true, updates are returned as a streaming response.
Accept | header | The requested content type for the dtab ([`application/dtab`][dtab] or `application/json`).

Response Code | Description
------------- | -----------
200           | Ok
404           | Dtab namespace does not exist

Content-types: application/json, [application/dtab][dtab]

### POST /api/1/dtabs/&lt;namespace&gt;

> Sample request

```
curl :4180/api/1/dtabs/pandoracorn -X POST -d '/foo => /bar' -H 'Content-Type: application/dtab'
```

> Sample response (204 NO CONTENT)

Creates a new dtab with the given namespace.  The post body should contain the
dtab to create and can be in json or dtab format.

Parameter | Type | Description
--------- | ---- | -----------
namespace | path | The dtab namespace to create.
Content-Type | header | The content type of the provided dtab ([`application/dtab`][dtab] or `application/json`).
N/A | post-body | The dtab to create.

Response Code | Description
------------- | -----------
204           | Created
400           | Dtab is malformed
409           | Dtab namespace already exists

### PUT /api/1/dtabs/&lt;namespace&gt;

> Sample request

```
curl :4180/api/1/dtabs/pandoracorn -X PUT -d '/bar => /bas' -H 'Content-Type: application/dtab'
```

> Sample response (204 NO CONTENT)

Modifies an existing dtab.  The post body should contain the updated dtab.

Parameter | Type | Description
--------- | ---- | -----------
namespace | path | The dtab namespace to update.
Content-Type | header | The content type of the provided dtab ([`application/dtab`][dtab] or `application/json`).
If-Match | header | If provided, the update will only be applied if the If-Match header matches the current dtab version.  This can be used to prevent conflicting updates.
N/A | post-body | The dtab to create.

Response Code | Description
------------- | -----------
204           | Updated
400           | Dtab is malformed
404           | Dtab namespace does not exist
412           | If-Match header is provided and does not match the current dtab version

### DELETE /api/1/dtabs/&lt;namespace&gt;

> Sample request

```
curl :4180/api/1/dtabs/pandoracorn -X DELETE
```

> Sample response (204 NO CONTENT)

Deletes an existing dtab with the given namespace.  Returns status code 404 if
the dtab namespace does not exist.

Parameter | Type | Description
--------- | ---- | -----------
namespace | path | The dtab namespace to delete.

Response Code | Description
------------- | -----------
204           | Deleted
404           | Dtab namespace does not exist

### GET /api/1/bind/&lt;namespace&gt;

> Sample request

```
curl ':4180/api/1/bind/default?path=/http/1.1/GET/default'
```

> Sample response

```
{
   "bound" : {
      "addr" : {
         "type" : "bound",
         "meta" : {},
         "addrs" : [
            {
               "meta" : {},
               "ip" : "127.0.0.1",
               "port" : 9990
            }
         ]
      },
      "id" : "/#/io.l5d.fs/default",
      "path" : "/"
   },
   "type" : "leaf"
}
```

Returns the bound tree for the given logical name in the context of the given
namespace.

Parameter | Type | Description
--------- | ---- | -----------
namespace | path | The dtab namespace to use.
path | uri | The logical name to bind.
dtab | uri | Additional dtab entries to use, in [application/dtab][dtab] format.
watch | uri | If true, updates are returned as a streaming response.

Response Code | Description
------------- | -----------
200           | Ok
400           | Path is malformed

Content-types: application/json

### GET /api/1/addr/&lt;namespace&gt;

> Sample request

```
curl ':4180/api/1/addr/default?path=/%23/io.l5d.fs/default'
```

> Sample response

```
{
   "addrs" : [
      {
         "meta" : {},
         "ip" : "127.0.0.1",
         "port" : 9990
      }
   ],
   "meta" : {},
   "type" : "bound"
}
```

Returns the addresses for a given concrete name.

Parameter | Type | Description
--------- | ---- | -----------
namespace | path | The dtab namespace to use.
path | uri | The logical name to bind.
watch | uri | If true, updates are returned as a streaming response.

Response Code | Description
------------- | -----------
200           | Ok
400           | Path is malformed

Content-types: application/json

### GET /api/1/resolve/&lt;namespace&gt;

> Sample request

```
curl ':4180/api/1/bind/resolve?path=/http/1.1/GET/default'
```

> Sample response

```
{
   "type" : "bound",
   "meta" : {},
   "addrs" : [
      {
         "ip" : "127.0.0.1",
         "meta" : {},
         "port" : 9990
      }
   ]
}
```

Returns the addresses for a given logical name.  This is effectively  a
combination of the bind and addr APIs.

Parameter | Type | Description
--------- | ---- | -----------
namespace | path | The dtab namespace to use.
path | uri | The logical name to bind.
dtab | uri | Additional dtab entries to use, in [application/dtab][dtab] format.
watch | uri | If true, updates are returned as a streaming response.

Response Code | Description
------------- | -----------
200           | Ok
400           | Path is malformed

Content-types: application/json

### GET /api/1/delegate/&lt;namespace&gt;

> Sample request

```
curl ':4180/api/1/bind/delegate?path=/svc/default'
```

> Sample response

```
{
   "path" : "/svc/default",
   "type" : "delegate",
   "delegate" : {
      "bound" : {
         "path" : "/",
         "addr" : {
            "type" : "bound",
            "addrs" : [
               {
                  "meta" : {},
                  "ip" : "127.0.0.1",
                  "port" : 9990
               }
            ],
            "meta" : {}
         },
         "id" : "/#/io.l5d.fs/default"
      },
      "type" : "leaf",
      "dentry" : {
         "prefix" : "/svc",
         "dst" : "/#/io.l5d.fs"
      },
      "path" : "/#/io.l5d.fs/default"
   }
}
```

Returns a delegation tree for a given logical name which shows each step of the
delegation process.

Parameter | Type | Description
--------- | ---- | -----------
namespace | path | The dtab namespace to use.
path | uri | The logical name to bind.
dtab | uri | Additional dtab entries to use, in [application/dtab][dtab] format.
watch | uri | If true, updates are returned as a streaming response.

Response Code | Description
------------- | -----------
200           | Ok
400           | Path is malformed

Content-types: application/json

### GET /api/1/bound-names

> Sample Request

```
curl :4180/api/1/bound-names
```

> Sample Response

```
[
   "/#/io.l5d.fs/default"
]
```

Returns a list of concrete names that Namerd knows about.

Parameter | Type | Description
--------- | ---- | -----------
watch | uri | If true, updates are returned as a streaming response.

Response Code | Description
------------- | -----------
200           | Ok

Content-types: application/json

[dtab]: https://linkerd.io/in-depth/dtabs/
