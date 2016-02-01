# Configuration

linkerd's configuration is controlled via config file, which must be provided
as a command-line argument. It may be a local file path or `-` to
indicate that the configuration should be read from the standard input.
For convenience, the release package includes a default `linkerd.yaml` file in
the `config/` directory.

## File Format

The configuration may be specified as a JSON or YAML object, as described
below.

```yaml
admin:
  port: 9990

baseDtab: |
  /host     => /$/io.l5d.fs;
  /method   => /$/io.buoyant.http.anyMethodPfx/host;
  /http/1.1 => /method;
  /ext/http => /host/web;

routers:
- protocol: http
  httpUriInDst: true

- protocol: http
  servers:
  - port: 8080
    ip: 0.0.0.0
    tls:
      certPath: /foo/cert
      keyPath: /foo/key
  timeoutMs: 1000
  dstPrefix: /ext/http

- protocol: thrift
  thriftFramed: false
  thriftMethodInDst: false
  baseDtab: |
    /thrift => /$/io.l5d.fs/thrift;
```

There are no requirements on field ordering, though it's generally
good style to start a router with the _protocol_.

The most minimal configuration looks something like the following,
which forwards all requests on `localhost:8080` to `localhost:8888`.

```yaml
routers:
- protocol: http
  baseDtab: /http => /$/inet/127.1/8888
  servers:
  - port: 8080
```

## Administrative interface

linkerd supports an administrative interface, both as a web ui and a collection
of json endpoints. The exposed admin port is configurable via a top-level
`admin` section:

* *admin* -- Config section for the admin interface, contains keys:
  * *port* -- Port for the admin interface (default is `9990`)

For example:
```yaml
admin:
  port: 9990
```

## Routers

All configurations must define a **routers** key, the value of which
must be an array of router configurations. Each router implements RPC
for a supported protocol. (linkerd doesn't need to understand the payload in an
RPC call, but it does need to know enough about the protocol to determine the
logical name of the destination.)

Routers also include **servers**, which define their entry points.

Additionally, any of the [basic router params](#basic-router-params)
may be specified in the top-level object as defaults.

Each router must be configured as an object with the following params:

* **protocol** -- a protocol name must match one of the loaded configuration plugins (e.g. _http_, _mux_).
  linkerd currently supports the following protocols:
  * [HTTP/1.1](#protocol-http)
  * [Thrift](#protocol-thrift)
  * [Mux](#protocol-mux) (experimental)
* [basic router param](#basic-router-params) or [protocol-specific router param](#proto-router-params)
* **servers** -- a list of server objects with the following params:
  * [basic server params](#basic-server-params) or [protocol-specific server params](#proto-server-params)

<a name="basic-router-params"></a>
### Basic router parameters

* *label* -- The name of the router (in stats and the admin ui). If
not specified, the protocol name is used.
* *baseDtab* -- Sets the base delegation table. See [Routing](#routing) for more.
* *dstPrefix* -- A path prefix to be used on request
destinations. Protocols may set defaults.
* *failFast* -- If `true`, connection failures are punished more
aggressively. Should not be used with small destination pools.
* *timeoutMs* -- Per-request timeout in milliseconds.

<!-- TODO router capacity  -->

<a name="proto-router-params"></a>
### Protocol-specific router parameters

<a name="protocol-http"></a>
#### HTTP/1.1

HTTP requests are routed by a combination of Host header, method, and URI. Specifically, HTTP/1.0 logical names are of the form:
```
  dstPrefix / "1.0" / method [/ uri* ]
```
and HTTP/1.1 logical names are of the form:
```
  dstPrefix / "1.1" / method / host [/ uri* ]
```

The default _dstPrefix_ is `/http`. In both cases, `uri` is only considered a part
of the logical name if the config option `httpUriInDst` is true.

Configuration optionns include:
* *httpAccessLog* -- Sets the access log path.  If not specified, no
access log is written.
* *httpUriInDst* -- If `true` http paths are appended to destinations
(to allow path-prefix routing).

<a name="protocol-thrift"></a>
#### Thrift

Since the Thrift protocol does not encode a destination name in the message
itself, routing must be done per port. This implies one port per Thrift
service. For out-of-the-box configuration, this means that the contents of
`disco/thrift` will be treated as a newline-delimited list of `host:port`
combinations for a specific thrift service.

The default _dstPrefix_ is `/thrift`.

* *thriftFramed* -- if `true`, a framed thrift transport is used; otherwise,
  a buffered transport is used.
* *thriftMethodInDst* -- if `true`, thrift method names are appended to
  destinations.

<a name="protocol-mux"></a>
#### Mux (experimental)

linkerd experimentally supports the [mux protocol](http://twitter.github.io/finagle/guide/Protocols.html#mux).

The default _dstPrefix_ is `/mux`.

<a name="basic-server-params"></a>
### Basic server parameters

* *label* -- The name of the server (in stats and the admin ui)
* *port* -- The TCP port number. Protocols may provide default
values. If no default is provided, the port parameter is required.
* *ip* -- The local IP address.  By default, the loopback address is
used.  A value like `0.0.0.0` configures the server to listen on all
local IPv4 interfaces.
* *tls* -- The server will serve over TLS if this parameter is provided.
  It must be an object containing keys:
  * *certPath* -- File path to the TLS certificate file
  * *keyPath* -- File path to the TLS key file

<a name="proto-server-params"></a>
### Protocol-specific server parameters

#### HTTP/1.1

The default _port_ is 4140.

#### Thrift

The default _port_ is 4114.

* *thriftFramed* -- if `true`, a framed thrift transport is used; otherwise, a buffered transport is used.
* *thriftMethodInDst* -- if `true`, thrift method names are appended to
  destinations.

#### Mux (experimental)

The default _port_ is 4141.

## Service discovery and naming

linkerd supports a variety of common service discovery backends, including
ZooKeeper and Consul. linkerd provides abstractions on top of service discovery
lookups that allow the use of arbitrary numbers of service discovery backends,
and for precedence and failover rules to be expressed between them. This logic
is governed by the [routing](#routing) configuration.

linkerd also ships with a simple file-based service discovery mechanism that
can be used for simple configurations.

Naming and service discovery are configured via the `namers` section of the
configuration file. In this file, `namers` is an array of objects, consisting
of the following parameters:

* *kind* -- One of the supported namer plugins, by fully-qualified class name.
  Current plugins include:
  * *io.l5d.fs*: [File-based service discovery](#disco-file)
  * *io.l5d.serversets*: [ZooKeeper ServerSets service discovery](#zookeeper)
  * *io.l5d.experimental.consul*: [Consul service discovery](#consul) (**experimental**)
  * *io.l5d.experimental.k8s*: [Kubernetes service discovery](#disco-k8s) (**experimental**)
* *prefix* -- This namer will resolve names beginning with this prefix. See
  [Configuring routing](#configuring-routing) for more on names. Some namers may
  configure a default prefix; see the specific namer section for details.
* *namer-specific parameters*.

<a name="disco-file"></a>
### File-based service discovery

linkerd ships with a simple file-based service discovery mechanism, called the
*file-based namer*. This system is intended to act as a structured form of
basic host lists.

While simple, the file-based namer is a full-fledged service discovery system
and can be used in production systems where host configurations are largely
static. Since its easy to modify, it is also convenient starting point for
systems that want to upgrade to a dedicated service discovery endpoint.
Finally, when chained with precedence rules, it can also a convenient way to
add local service discovery overrides for debugging or experimental purposes.

This service discovery mechanism is tied to the directory set by the
`namers/rootDir` key in `config.yaml`. This directory must be on the local
filesystem and relative to linkerd's start path. Every file in this directory
corresponds to a service, where the name of the file is the service's _concrete
name_, and the contents of the file must be a newline-delimited set of
addresses.

For example, the directory might look like this:

```bash
$ ls disco/
apps    users   web
```
And the contents of the files might look like this:

```bash
$ cat config/web
192.0.2.220 8080
192.0.2.105 8080
192.0.2.210 8080
```

linkerd watches all files in this directory with a 10-second refresh time, so
files can be added, removed, or updated, and linkerd will pick up the changes
automatically.

The file-based namer is configured with kind `io.l5d.fs`, and these parameters:

* *rootDir* -- the directory containing name files as described above.

For example:
```yaml
namers:
- kind: io.l5d.fs
```

The default _prefix_ for the file-based namer is `io.l5d.fs`.

Once configured, to use the file-based namer, you must reference it in
the dtab. For example:
```
baseDtab: |
  /host     => /$/io.l5d.fs;
  /method   => /$/io.buoyant.http.anyMethodPfx/host;
  /http/1.1 => /method;
  /ext/http => /host/web;
```

<a name="zookeeper"></a>
### ZooKeeper ServerSets service discovery

linkerd provides support for [ZooKeeper
ServerSets]:(https://twitter.github.io/commons/apidocs/com/twitter/common/zookeeper/ServerSet.html).

The ServerSets namer is configured with kind `io.l5d.serversets`, and these parameters:

* *zkAddrs* -- list of ZooKeeper hosts.
* *host* --  the ZooKeeper host.
* *port* --  the ZooKeeper port.

For example:
```yaml
namers:
- kind: io.l5d.serversets
  zkAddrs:
  - host: 127.0.0.1
    port: 2181
```

The default _prefix_ is `io.l5d.serversets`.

Once configured, to use the ServerSets namer, you must reference it in
the dtab. For example:
```
baseDtab: |
  /host     => /$/io.l5d.fs;
  /host     => /$/io.l5d.serversets/foo/bar;
  /method   => /$/io.buoyant.http.anyMethodPfx/host;
  /http/1.1 => /method;
  /ext/http => /host/web;
```

<a name="consul"></a>
### Consul service discovery (experimental)

linkerd provides support for service discovery via
[Consul](https://www.consul.io/). Note that this support is still considered
experimental.

The Consul namer is configured with kind `io.l5d.experimental.consul`, and these parameters:

* *host* --  the Consul host. (default: localhost)
* *port* --  the Consul port. (default: 8500)

For example:
```yaml
namers:
- kind: io.l5d.experimental.consul
  - host: 127.0.0.1
    port: 2181
```

The default _prefix_ is `io.l5d.consul`. (Note that this is *different* from
the name in the configuration block.)

Once configured, to use the Consul namer, you must reference it in
the dtab. The Consul namer takes one parameter in its path, which is the Consul
datacenter. For example:
```
baseDtab: |
  /host     => /$/io.l5d.fs;
  /host     => /$/io.l5d.consul/dc1;
  /method   => /$/io.buoyant.http.anyMethodPfx/host;
  /http/1.1 => /method;
  /ext/http => /host/web;
```

<a name="disco-k8s"></a>
### Kubernetes service discovery (experimental)

linkerd provides support for service discovery via
[Kubernetes](https://k8s.io/). Note that this support is still considered
experimental.

The Kubernetes namer is configured with kind `io.l5d.experimental.k8s`, and these parameters:

* *host* -- the Kubernetes master host. (default: kubernetes.default.cluster.local)
* *port* -- the Kubernetes master port. (default: 443)
* *tls* -- Whether TLS should be used in communicating with the Kubernetes master. (default: true)
* *tlsWithoutValidation* -- Whether certificate-checking should be disabled. (default: false)
* *authTokenFile* -- Path to a file containing the Kubernetes master's authorization token.
  (default: /var/run/secrets/kubernetes.io/serviceaccount/token)

For example:
```yaml
namers:
- kind: io.l5d.experimental.k8s
  - host: kubernetes.default.cluster.local
    port: 443
    tls: true
    authTokenFile: /var/run/secrets/kubernetes.io/serviceaccount/token
```

The default _prefix_ is `io.l5d.k8s`. (Note that this is *different* from
the name in the configuration block.)

The Kubernetes namer takes three path components: `namespace`, `port-name` and
`svc-name`:

* namespace: the Kubernetes namespace.
* port-name: the port name.
* svc-name: the name of the service.

Once configured, to use the Kubernetes namer, you must reference it in
the dtab.
```
baseDtab: |
  /host     => /$/io.l5d.fs;
  /host     => /$/io.l5d.k8s/prod/http;
  /method   => /$/io.buoyant.http.anyMethodPfx/host;
  /http/1.1 => /method;
  /ext/http => /host/web;
```

<a name="configuring-routing"></a>
## Configuring routing

Routing rules determine the mapping between a service's logical name and a
concrete name. (See [Basic concepts](#basic-concepts) for more.)

Routing is described via a "delegation table", or **dtab**. As noted in
[basic router parameters](#basic-router-params), this is configured via the
`baseDtab` parameter in the configuration object.

<a name="routing-overrides"></a>
### Per-request routing overrides

For HTTP RPC calls, the "Dtab-local" header is interpreted by linkerd as an
additional rule to be appended to the base dtab.
