# Namers

*(for the [namers](config.md#namers) key)*

A namer binds a concrete name to a physical address.
http://twitter.github.io/finagle/guide/Names.html

A namer config block has the following parameters:

* *kind* -- The name of the namer plugin
* *prefix* -- This namer will resolve names beginning with `/#/<prefix>`.  Some
  namers may configure a default prefix; see the specific namer section for
  details.
* *experimental* -- Set this to `true` to enable the namer if it is experimental.
* *namer-specific parameters*.

### Example

```yaml
namers:
- kind: io.l5d.fs
  prefix: /disco
  rootDir: disco
```

<a name="fs"></a>
## File-based service discovery

`io.l5d.fs`

linkerd ships with a simple file-based service discovery mechanism, called the
*file-based namer*. This system is intended to act as a structured form of
basic host lists.

While simple, the file-based namer is a full-fledged service discovery system,
and can be useful in production systems where host configurations are largely
static. It can act as an upgrade path for the introduction of an external
service discovery system, since application code will be isolated from these
changes. Finally, when chained with precedence rules, the file-based namer can
be a convenient way to add local service discovery overrides for debugging or
experimentation.

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

linkerd watches all files in this directory, so files can be added, removed, or
updated, and linkerd will pick up the changes automatically.

The file-based namer is configured with kind `io.l5d.fs`, and these parameters:

* *rootDir* -- the directory containing name files as described above.

For example:
```yaml
namers:
- kind: io.l5d.fs
  rootDir: disco
```

The default _prefix_ for the file-based namer is `io.l5d.fs`.

Once configured, to use the file-based namer, you must reference it in
the dtab. For example:
```
baseDtab: |
  /http/1.1/* => /#/io.l5d.fs
```

<a name="serversets"></a>
## ZooKeeper ServerSets service discovery

`io.l5d.serversets`

linkerd provides support for [ZooKeeper
ServerSets](https://twitter.github.io/commons/apidocs/com/twitter/common/zookeeper/ServerSet.html).

The ServerSets namer is configured with kind `io.l5d.serversets`, and these parameters:

* *zkAddrs* -- list of ZooKeeper addresses:
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
  /http/1.1/* => /#/io.l5d.serversets/discovery/prod;
```

<a name="consul"></a>
## Consul service discovery (experimental)

`io.l5d.consul`

linkerd provides support for service discovery via
[Consul](https://www.consul.io/). Note that this support is still considered
experimental so you must set `experimental: true` to use this namer.

The Consul namer is configured with kind `io.l5d.consul`, and these parameters:

* *host* --  the Consul host. (default: localhost)
* *port* --  the Consul port. (default: 8500)

For example:
```yaml
namers:
- kind: io.l5d.consul
  experimental: true
  host: 127.0.0.1
  port: 2181
```

The default _prefix_ is `io.l5d.consul`.

Once configured, to use the Consul namer, you must reference it in
the dtab. The Consul namer takes one parameter in its path, which is the Consul
datacenter. For example:
```
baseDtab: |
  /http/1.1/* => /#/io.l5d.consul/dc1;
```

<a name="k8s"></a>
## Kubernetes service discovery (experimental)

`io.l5d.k8s`

linkerd provides support for service discovery via
[Kubernetes](https://k8s.io/). Note that this support is still considered
experimental so you must set `experimental: true` to use this namer.

The Kubernetes namer is configured with kind `io.l5d.k8s`, and these parameters:

* *host* -- the Kubernetes master host. (default: localhost)
* *port* -- the Kubernetes master port. (default: 8001)

For example:
```yaml
namers:
- kind: io.l5d.k8s
  experimental: true
  host: localhost
  port: 8001
```

The Kubernetes namer does not support TLS.  Instead, you should run `kubectl proxy` on each host
which will create a local proxy for securely talking to the Kubernetes cluster API.

The default _prefix_ is `io.l5d.k8s`.

The Kubernetes namer takes three path components: `namespace`, `port-name` and
`svc-name`:

* namespace: the Kubernetes namespace.
* port-name: the port name.
* svc-name: the name of the service.

Once configured, to use the Kubernetes namer, you must reference it in
the dtab.
```
baseDtab: |
  /http/1.1/* => /#/io.l5d.k8s/prod/http;
```

<a name="marathon"></a>
## Marathon service discovery (experimental)

`io.l5d.marathon`

linkerd provides support for service discovery via
[Marathon](https://mesosphere.github.io/marathon/). Note that this support is still considered
experimental so you must set `experimental: true` to use this namer.

The Marathon namer is configured with kind `io.l5d.marathon`, and these parameters:

* *host* -- the Marathon master host. (default: marathon.mesos)
* *port* -- the Marathon master port. (default: 80)
* *uriPrefix* -- the Marathon API prefix. (default: empty string). This prefix
  depends on your Marathon configuration. For example, running Marathon
  locally, the API is avaiable at `localhost:8080/v2/`, while the default setup
  on AWS/DCOS is `$(dcos config show core.dcos_url)/marathon/v2/apps`.
* *ttlMs* -- the polling timeout in milliseconds against the marathon API
  (default: 5000)

For example:
```yaml
namers:
- kind:         io.l5d.marathon
  experimental: true
  prefix:       /#/io.l5d.marathon
  host:         marathon.mesos
  port:         80
  uriPrefix:    /marathon
  ttlMs:        500
```

The default _prefix_ is `io.l5d.marathon`.

The Marathon namer takes any number of path components. The path should
correspond to the app id of a marathon application. For example, the app with
id "/users" can be reached with `/#/io.l5d.marathon/users`. Likewise, the app
with id "/appgroup/usergroup/users" can be reached with
`/#/io.l5d.marathon/appgroup/usergroup/users`.

Once configured, to use the Marathon namer, you must reference it in
the dtab.
```
baseDtab: |
  /marathonId => /#/io.l5d.marathon;
  /host       => /$/io.buoyant.http.domainToPathPfx/marathonId;
  /http/1.1/* => /host;
```

<a name="zkLeader"></a>
## ZooKeeper Leader

`io.l5d.zkLeader`

A namer backed by ZooKeeper leader election. The path processed by this namer is treated as the
ZooKeeper path of a leader group. The namer resolves to the address stored in the data of the
leader.

The ZooKeeper Leader namer is configured with kind `io.l5d.zkLeader` and these parameters:

* *zkAddrs* -- list of ZooKeeper addresses:
  * *host* --  the ZooKeeper host.
  * *port* --  the ZooKeeper port.
