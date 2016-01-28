# Configuration

linkerd's configuration is controlled via config file, which must be provided
as a command-line argument. It may be a local file path or `-` to
indicate that the configuration should be read from the standard input.
For convenience, the release package includes a default `linkerd.yaml` file in
the `config/` directory.

## Format

The configuration may be specified as a JSON or YAML object, as described
below.

```yaml
admin:
  port: 9990

baseDtab: |
  /host     => /$/io.buoyant.fs;
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
  baseDtab: |
    /thrift => /$/io.buoyant.fs/thrift;
```

There are no requirements on field ordering, though it's generally
good style to start a router with the _protocol_.

The most minimal configuration looks something like the following,
which forwards all requests on localhost:8080 to localhost:8888.

```yaml
routers:
- protocol: http
  baseDtab: /http => /$/inet/127.1/8888
  servers:
  - port: 8080
```

## Routers

All configurations must define a **routers** key, the value of which
must be an array of router configurations. Each router implements RPC
for a supported protocol. linkerd doesn't need to understand the payload
in an RPC call, but it does need enough protocol support to determine the
logical name of the destination.

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

HTTP requests are routed by Host header, i.e. the Host header determines the
_logical name_ of the server. (See [Basic concepts](https://linkerd.io/doc/latest/userguide/#basic-concepts) for more
on logical names.)

The default _dstPrefix_ is `/http` so that requests
receive destinations in the form _/http/VERSION/METHOD/HOST_.

* *httpAccessLog* -- Sets the access log path.  If not specified, no
access log is written.
* *httpUriInDst* -- If `true` http paths are appended to destinations
(to allow path-prefix routing)

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
* *thriftMethodInDst* -- allows routing based on the thrift method name.

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
* *tls* -- The server will serve over TLS if this parameter is provided.  It must be an object containing keys:
** *certPath* -- File path to the TLS certificate file
** *keyPath* -- File path to the TLS key file

<a name="proto-server-params"></a>
### Protocol-specific server parameters

#### HTTP/1.1

The default _port_ is 4140.

#### Thrift

The default _port_ is 4114.

* *thriftFramed* -- if `true`, a framed thrift transport is used; otherwise, a buffered transport is used.


#### Mux (experimental)

The default _port_ is 4141.

## Admin

A configuration may define an **admin** key which is an object that configures
the admin http interface.

* *port* -- The port on which to serve the admin http interface (default `9990`)

## Configuring service discovery and naming

linkerd currently supports a file-based service discovery mechanism.
(Upcoming releases will add support for Consul, Zookeeper, and etcd.)

Service discovery access is controlled by linkerd's routing configuration. This
gives linkerd the ability to access multiple service discovery mechanisms and to
express precedence and failover logic between them. See [Routing](#routing) for more.

Naming and service discovery are configured via the `namers` section of the configuration
file. `namers` is an array of objects, consisting of the following parameters:

* *kind* -- One of the supported namer plugins, by fully-qualified class name.
  Current plugins include:
  * *io.l5d.fs*: [File-based service discovery](#disco-file)
  * *io.l5d.experimental.k8s*: [Kubernetes service discovery](#disco-k8s) (**experimental**)
* *prefix* -- This namer will resolve names beginning with this prefix. See
  [Configuring routing](#configuring-routing) for more on names. Some namers may
  configure a default prefix; see the specific namer section for details.
* *namer-specific parameters* -- See the namer sections below.

<a name="disco-file"></a>
### File-based service discovery

linkerd ships with a basic file-based service discovery mechanism, suitable for
simple configurations.

This service discovery mechanism is tied to a directory, determined by the
`namers/rootDir` key in `config.yaml`. This directory must be on the local
filesystem and relative to linkerd's start path. linkerd watches all files in
this directory, with a 10-second refresh time.

Each file in the config directory corresponds to a service, where the name of
the file is the service's _concrete name_, and the contents of the file must be
a newline-delimited set of "physical" endpoints.

For instance, if you are using linkerd to route traffic to three different
services, then your `disco/` directory might look like this:

```bash
$ ls disco/
apps    users   web
```
And the contents of one of the files might look like this:

```bash
$ cat config/web
10.187.245.220 9999
10.187.137.105 9999
10.187.94.210 9999
```
Filesystem namer parameters:

The default _prefix_ is `io.l5d.fs`.

* *rootDir* -- the directory containing name files as described above.

<a name="disco-k8s"></a>
### Kubernetes service discovery (experimental)

An experimental namer for [Kubernetes](http://kubernetes.io/)-based systems.

Kubernetes namer parameters:

The default _prefix_ is `io.l5d.k8s`.

* *host* --  the Kubernetes master host. (default: kubernetes.default.cluster.local)
* *port* --  the Kubernetes master port. (default: 443)
* *tls* --  Whether TLS should be used in communicating with the Kubernetes master. (default: true)
* *tlsWithoutValidation* -- Whether certificate-checking should be disabled. (default: false)
* *authTokenFile* -- Path to a file containing the Kubernetes master's authorization token.
  (default: /var/run/secrets/kubernetes.io/serviceaccount/token)

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

More TBD.