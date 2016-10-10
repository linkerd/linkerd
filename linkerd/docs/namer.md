# Namers

```yaml
namers:
- kind: io.l5d.fs
  prefix: /disco
  rootDir: disco
```

A namer binds a [concrete name to a physical address](http://twitter.github.io/finagle/guide/Names.html).

<aside class="notice">
These parameters are available to the namer regardless of kind. Namers may also have kind-specific parameters.
</aside>

Key | Default Value | Description
--- | ------------- | -----------
kind | _required_ | Either `io.l5d.fs`, `io.l5d.serversets`, `io.l5d.consul`, `io.l5d.k8s`, `io.l5d.marathon`, or `io.l5d.zkLeader`.
prefix | namer dependent | Resolves names with `/#/<prefix>`.
experimental | `false` | Set this to `true` to enable the namer if it is experimental.

<a name="fs"></a>
## File-based service discovery

kind: `io.l5d.fs`

### File-based Configuration

> Example fs configuration:

```yaml
namers:
- kind: io.l5d.fs
  rootDir: disco
```

> Then reference the namer in the dtab to use it:

```
baseDtab: |
  /http/1.1/* => /#/io.l5d.fs
```

> With the filesystem directory:

```bash
$ ls disco/
apps    users   web
```

> The contents of the files look similar to this:

```bash
$ cat config/web
192.0.2.220 8080
192.0.2.105 8080
192.0.2.210 8080
```

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

linkerd watches all files in this directory, so files can be added, removed, or
updated, and linkerd will pick up the changes automatically.

Key | Default Value | Description
--- | ------------- | -----------
prefix | `io.l5d.fs` | Resolves names with `/#/<prefix>`.
rootDir | _required_ | the directory containing name files as described above.

### File-based Path Parameters

> Dtab Path Format:

```yaml
/#/<prefix>/<fileName>
```

Key | Required | Description
--- | -------- | -----------
prefix | yes | Tells linkerd to resolve the request path using the fs namer.
fileName | yes | The file in `rootDir` to use when resolving this request.

<a name="serversets"></a>
## ZooKeeper ServerSets service discovery

kind: `io.l5d.serversets`


### ServerSets Configuration

> Example ServerSets configuration:

```yaml
namers:
- kind: io.l5d.serversets
  zkAddrs:
  - host: 127.0.0.1
    port: 2181
```

> Then reference the namer in the dtab to use it:

```yaml
baseDtab: |
  /http/1.1/* => /#/io.l5d.serversets/discovery/prod;
```

linkerd provides support for [ZooKeeper
ServerSets](https://twitter.github.io/commons/apidocs/com/twitter/common/zookeeper/ServerSet.html).

Key | Default Value | Description
--- | ------------- | -----------
prefix | `io.l5d.serversets` | Resolves names with `/#/<prefix>`.
zkAddrs | _required_ | A list of ZooKeeper addresses, each of which have `host` and `port` parameters.

### ServerSets Path Parameters

> Dtab Path Format:

```yaml
/#/<prefix>/<zkHosts>/<zkPath>[:<endpoint>]
```

Key | Required | Description
--- | -------- | -----------
prefix | yes | Tells linkerd to resolve the request path using the serversets namer.
zkHosts | yes | The ZooKeeper host to use for this request.
zkPath | yes | The ZooKeeper path to use for this request.
endpoint | no | The ZooKeeper endpoint to use for this request.

<a name="consul"></a>
## Consul service discovery (experimental)

kind: `io.l5d.consul`

### Consul Configuration

> Configure a consul namer:

```yaml
namers:
- kind: io.l5d.consul
  experimental: true
  host: 127.0.0.1
  port: 2181
  includeTag: true
  useHealthCheck: true
  setHost: true
  consistencyMode: stale
```

> Then reference the namer in the dtab to use it:

```
baseDtab: |
  /http/1.1/* => /#/io.l5d.consul/dc1/prod;
```

linker provides support for service discovery via [Consul](https://www.consul.io/).

Key | Default Value | Description
--- | ------------- | -----------
prefix | `io.l5d.consul` | Resolves names with `/#/<prefix>`.
experimental | _required_ | Because this namer is still considered experimental, you must set this to `true` to use it.
host | `localhost` | The Consul host.
port | `8500` | The Consul port.
includeTag | `false` | If `true`, read a Consul tag from the path.
useHealthCheck | `false` | If `true`, rely on Consul health checks.
token | no authentication | The auth token to use when making API calls.
setHost | `false` | If `true`, HTTP requests resolved by Consul will have their Host header overwritten to `${serviceName}.service.${datacenter}.${domain}`. `$domain` is fetched from Consul.
consistencyMode | `default` | Select between [Consul API consistency modes](https://www.consul.io/docs/agent/http.html) such as `default`, `stale` and `consistent`.
failFast | `false` | If `false`, disable fail fast and failure accrual for Consul client. Keep it `false` when using a local agent but change it to `true` when talking directly to an HA Consul API.

### Consul Path Parameters

> Dtab Path Format when includeTag is false

```yaml
/#/<prefix>/<datacenter>/<serviceName>
```

> Dtab Path Format when includeTag is true

```yaml
/#/<prefix>/<datacenter>/<tag>/<serviceName>
```

Key | Required | Description
--- | -------- | -----------
prefix | yes | Tells linkerd to resolve the request path using the consul namer.
datacenter | yes | The Consul datacenter to use for this request. It can have a value `.local` (otherwise invalid datacenter name from Consul's perspective) in order to reference a datacenter of the agent namer is connected to.
tag | yes if includeTag is `true` | The Consul tag to use for this request.
serviceName | yes | The Consul service name to use for this request.


<a name="k8s"></a>
## Kubernetes service discovery (experimental)

kind : `io.l5d.k8s`

### K8s Configuration

> Configure a K8s namer

```yaml
namers:
- kind: io.l5d.k8s
  experimental: true
  host: localhost
  port: 8001
```

> Then reference the namer in the dtab to use it:

```
baseDtab: |
  /http/1.1/* => /#/io.l5d.k8s/prod/http;
```

linkerd provides support for service discovery via
[Kubernetes](https://k8s.io/).

Key | Default Value | Description
--- | ------------- | -----------
prefix | `io.l5d.k8s` | Resolves names with `/#/<prefix>`.
experimental | _required_ | Because this namer is still considered experimental, you must set this to `true` to use it.
host | `localhost` | The Kubernetes master host.
port | `8001` | The Kubernetes master post.

<aside class="notice">
The Kubernetes namer does not support TLS.  Instead, you should run `kubectl proxy` on each host
which will create a local proxy for securely talking to the Kubernetes cluster API. See (the k8s guide)[https://linkerd.io/doc/latest/k8s/] for more information.
</aside>

### K8s Path Parameters

> Dtab Path Format

```yaml
/#/<prefix>/<namespace>/<port-name>/<svc-name>
```

Key | Required | Description
--- | -------- | -----------
prefix | yes | Tells linkerd to resolve the request path using the k8s namer.
namespace | yes | The Kubernetes namespace.
port-name | yes | The port name.
svc-name | yes | The name of the service.

### K8s-External Configuration

> Configure a K8s-External namer

```yaml
namers:
- kind: io.l5d.k8s-external
  experimental: true
  host: localhost
  port: 8001
```

> Then reference the namer in the dtab to use it:

```
baseDtab: |
  /http/1.1/* => /#/io.l5d.k8s-external/prod/http;
```

The [Kubernetes](https://k8s.io/) External namer looks up the IP of the external
load balancer for the given service on the given port.  This can be used by
linkerd instances running outside of k8s to route to services running in k8s.

Key | Default Value | Description
--- | ------------- | -----------
prefix | `io.l5d.k8s-external` | Resolves names with `/#/<prefix>`.
experimental | _required_ | Because this namer is still considered experimental, you must set this to `true` to use it.
host | `localhost` | The Kubernetes master host.
port | `8001` | The Kubernetes master post.

<aside class="notice">
The Kubernetes namer does not support TLS.  Instead, you should run `kubectl proxy` on each host
which will create a local proxy for securely talking to the Kubernetes cluster API. See (the k8s guide)[https://linkerd.io/doc/latest/k8s/] for more information.
</aside>

### K8s-External Path Parameters

> Dtab Path Format

```yaml
/#/<prefix>/<namespace>/<port-name>/<svc-name>
```

Key | Required | Description
--- | -------- | -----------
prefix | yes | Tells linkerd to resolve the request path using the k8s-external namer.
namespace | yes | The Kubernetes namespace.
port-name | yes | The port name.
svc-name | yes | The name of the service.


<a name="marathon"></a>
## Marathon service discovery (experimental)

kind: `io.l5d.marathon`

### Marathon Configuration

> Configure a marathon namer

```yaml
namers:
- kind:         io.l5d.marathon
  experimental: true
  prefix:       /#/io.l5d.marathon
  host:         marathon.mesos
  port:         80
  uriPrefix:    /marathon
  ttlMs:        5000
```
> Then reference the namer in the dtab to use it:

```
baseDtab: |
  /marathonId => /#/io.l5d.marathon;
  /host       => /$/io.buoyant.http.domainToPathPfx/marathonId;
  /http/1.1/* => /host;
```

linkerd provides support for service discovery via
[Marathon](https://mesosphere.github.io/marathon/).

Key | Default Value | Description
--- | ------------- | -----------
prefix | `io.l5d.marathon` | Resolves names with `/#/<prefix>`.
experimental | _required_ | Because this namer is still considered experimental, you must set this to `true` to use it.
host | `marathon.mesos` | The Marathon master host.
port | `80` | The Marathon master port.
uriPrefix | none | The Marathon API prefix. This prefix depends on your Marathon configuration. For example, running Marathon locally, the API is avaiable at `localhost:8080/v2/`, while the default setup on AWS/DCOS is `$(dcos config show core.dcos_url)/marathon/v2/apps`.
ttlMs | `5000` | The polling interval in milliseconds against the marathon API.

### Marathon Path Parameters

> Dtab Path Format

```yaml
/#/<prefix>/<appId>
```

Key | Required | Description
--- | -------- | -----------
prefix | yes | Tells linkerd to resolve the request path using the marathon namer.
appId | yes | The app id of a marathon application. This id can be multiple path segments long. For example, the app with id "/users" can be reached with `/#/io.l5d.marathon/users`. Likewise, the app with id "/appgroup/usergroup/users" can be reached with `/#/io.l5d.marathon/appgroup/usergroup/users`.



<a name="zkLeader"></a>
## ZooKeeper Leader

kind: `io.l5d.zkLeader`

### ZK Leader Configuration

A namer backed by ZooKeeper leader election.

Key | Default Value | Description
--- | ------------- | -----------
prefix | `io.l5d.zkLeader` | Resolves names with `/#/<prefix>`.
zkAddrs | _required_ | A list of ZooKeeper addresses, each of which have `host` and `port` parameters.

### ZK Leader Path Parameters

> Dtab Path Format

```yaml
/#/<prefix>/<zkPath>
```

Key | Required | Description
--- | -------- | -----------
prefix | yes | Tells linkerd to resolve the request path using the marathon namer.
zkPath | yes | The ZooKeeper path of a leader group. This path can be multiple path segments long. The namer resolves to the address stored in the data of the leader.

