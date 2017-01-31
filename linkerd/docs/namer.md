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
kind | _required_ | Either [`io.l5d.fs`](#file-based-service-discovery), [`io.l5d.serversets`](#zookeeper-serversets-service-discovery), [`io.l5d.consul`](#consul-service-discovery), [`io.l5d.k8s`](#kubernetes-service-discovery), [`io.l5d.marathon`](#marathon-service-discovery-experimental), [`io.l5d.zkLeader`](#zookeeper-leader), [`io.l5d.curator`](#curator), or [`io.l5d.rewrite`](#rewrite).
prefix | namer dependent | Resolves names with `/#/<prefix>`.
experimental | `false` | Set this to `true` to enable the namer if it is experimental.
transformers | No transformers | A list of [transformers](#transformer) to apply to the resolved addresses.

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
dtab: |
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
dtab: |
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
## Consul service discovery

kind: `io.l5d.consul`

### Consul Configuration

> Configure a consul namer:

```yaml
namers:
- kind: io.l5d.consul
  host: 127.0.0.1
  port: 2181
  includeTag: true
  useHealthCheck: true
  setHost: true
  consistencyMode: stale
```

> Then reference the namer in the dtab to use it:

```
dtab: |
  /http/1.1/* => /#/io.l5d.consul/dc1/prod;
```

linker provides support for service discovery via [Consul](https://www.consul.io/).

Key | Default Value | Description
--- | ------------- | -----------
prefix | `io.l5d.consul` | Resolves names with `/#/<prefix>`.
host | `localhost` | The Consul host.
port | `8500` | The Consul port.
includeTag | `false` | If `true`, read a Consul tag from the path.
useHealthCheck | `false` | If `true`, exclude app instances that are failing Consul health checks. Even if `false`, linkerd's built-in resiliency algorithms will still apply.
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
## Kubernetes service discovery

kind : `io.l5d.k8s`

### K8s Configuration

> Configure a K8s namer

```yaml
namers:
- kind: io.l5d.k8s
  host: localhost
  port: 8001
  labelSelector: version
```

> Then reference the namer in the dtab to use it:

```
dtab: |
  /http/1.1/* => /#/io.l5d.k8s/prod/http;
```

linkerd provides support for service discovery via
[Kubernetes](https://k8s.io/).

Key | Default Value | Description
--- | ------------- | -----------
prefix | `io.l5d.k8s` | Resolves names with `/#/<prefix>`.
host | `localhost` | The Kubernetes master host.
port | `8001` | The Kubernetes master port.
labelSelector | none | The key of the label to filter services.

<aside class="notice">
The Kubernetes namer does not support TLS.  Instead, you should run `kubectl proxy` on each host
which will create a local proxy for securely talking to the Kubernetes cluster API. See (the k8s guide)[https://linkerd.io/doc/latest/k8s/] for more information.
</aside>

### K8s Path Parameters

> Dtab Path Format

```yaml
/#/<prefix>/<namespace>/<port-name>/<svc-name>[/<label-value>]
```

Key | Required | Description
--- | -------- | -----------
prefix | yes | Tells linkerd to resolve the request path using the k8s namer.
namespace | yes | The Kubernetes namespace.
port-name | yes | The port name.
svc-name | yes | The name of the service.
label-value | yes if `labelSelector` is defined | The value used to filter services.

### K8s External Configuration

> Configure a K8s External namer

```yaml
namers:
- kind: io.l5d.k8s.external
  experimental: true
  host: localhost
  port: 8001
  labelSelector: version
```

> Then reference the namer in the dtab to use it:

```
dtab: |
  /http/1.1/* => /#/io.l5d.k8s.external/prod/http;
```

The [Kubernetes](https://k8s.io/) External namer looks up the IP of the external
load balancer for the given service on the given port.  This can be used by
linkerd instances running outside of k8s to route to services running in k8s.

Key | Default Value | Description
--- | ------------- | -----------
prefix | `io.l5d.k8s.external` | Resolves names with `/#/<prefix>`.
experimental | _required_ | Because this namer is still considered experimental, you must set this to `true` to use it.
host | `localhost` | The Kubernetes master host.
port | `8001` | The Kubernetes master port.
labelSelector | none | The key of the label to filter services.

<aside class="notice">
The Kubernetes namer does not support TLS.  Instead, you should run `kubectl proxy` on each host
which will create a local proxy for securely talking to the Kubernetes cluster API. See (the k8s guide)[https://linkerd.io/doc/latest/k8s/] for more information.
</aside>

### K8s External Path Parameters

> Dtab Path Format

```yaml
/#/<prefix>/<namespace>/<port-name>/<svc-name>[/<label-value>]
```

Key | Required | Description
--- | -------- | -----------
prefix | yes | Tells linkerd to resolve the request path using the k8s external namer.
namespace | yes | The Kubernetes namespace.
port-name | yes | The port name.
svc-name | yes | The name of the service.
label-value | yes if `labelSelector` is defined | The label value used to filter services.


<a name="marathon"></a>
## Marathon service discovery (experimental)

kind: `io.l5d.marathon`

### Marathon Configuration

> Configure a marathon namer

```yaml
namers:
- kind:           io.l5d.marathon
  experimental:   true
  prefix:         /io.l5d.marathon
  host:           marathon.mesos
  port:           80
  uriPrefix:      /marathon
  ttlMs:          5000
  useHealthCheck: false
```
> Then reference the namer in the dtab to use it:

```
dtab: |
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
uriPrefix | none | The Marathon API prefix. This prefix depends on your Marathon configuration. For example, running Marathon locally, the API is available at `localhost:8080/v2/`, while the default setup on AWS/DCOS is `$(dcos config show core.dcos_url)/marathon/v2/apps`.
ttlMs | `5000` | The polling interval in milliseconds against the Marathon API.
useHealthCheck | `false` | If `true`, exclude app instances that are failing Marathon health checks. Even if `false`, linkerd's built-in resiliency algorithms will still apply.

### Marathon Path Parameters

> Dtab Path Format

```yaml
/#/<prefix>/<appId>
```

Key | Required | Description
--- | -------- | -----------
prefix | yes | Tells linkerd to resolve the request path using the marathon namer.
appId | yes | The app id of a marathon application. This id can be multiple path segments long. For example, the app with id "/users" can be reached with `/#/io.l5d.marathon/users`. Likewise, the app with id "/appgroup/usergroup/users" can be reached with `/#/io.l5d.marathon/appgroup/usergroup/users`.

### Marathon Authentication

> Example environment variable

```json
{
  "login_endpoint": "https://leader.mesos/acs/api/v1/auth/login",
  "private_key": "<private-key-value>",
  "scheme": "RS256",
  "uid": "service-acct"
}
```

The Marathon namer supports loading authentication data from a
`DCOS_SERVICE_ACCOUNT_CREDENTIAL` environment variable at boot time.

Further reading:

* [Mesosphere Docs](https://docs.mesosphere.com/1.8/administration/id-and-access-mgt/service-auth/custom-service-auth/)
* [Mesosphere Universe Repo](https://github.com/mesosphere/universe/search?utf8=%E2%9C%93&q=DCOS_SERVICE_ACCOUNT_CREDENTIAL)

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

<a name="curator"></a>
## Curator

kind: `io.l5d.curator`

### Curator Configuration

A namer that uses the Curator discovery library to resolve names.

Note: If you have registered Curator services with a custom payload object, that class file must be on the classpath. Otherwise you will get a `java.lang.IllegalArgumentException: Invalid type id '<some-payload-class'` error.

Key | Default Value | Description
--- | ------------- | -----------
prefix | `io.l5d.curator` | Resolves names with `/#/<prefix>`.
experimental | _required_ | Because this namer is still considered experimental, you must set this to `true` to use it.
zkAddrs | _required_ | A list of ZooKeeper addresses, each of which have `host` and `port` parameters.
basePath | `/` | The ZooKeeper path for Curator discovery.

### Curator Path Parameters

> Dtab Path Format

```yaml
/#/<prefix>/<serviceName>
```

Key | Required | Description
--- | -------- | -----------
prefix | yes | Tells linkerd to resolve the request path using the curator namer.
serviceName | yes | The name of the Curator service to lookup in ZooKeeper.

<a name="rewrite"></a>
## Rewrite

kind: `io.l5d.rewrite`

### Rewrite Configuration

> Example rewrite configuration:

```yaml
namers:
- kind: io.l5d.rewrite
  pattern: "/{service}/api"
  name: "/srv/{service}"
```

> Then reference the namer in the dtab to use it:

```
dtab: |
  /http => /#/io.l5d.rewrite
```

A namer that completely rewrites a path.  This is useful to do arbitrary
reordering of the path segments that is not possible using standard prefix
replacement.  While this is a general purpose tool for reordering path
segments, it cannot be used to modify or split individual segments (for
modification or splitting of individual segments, see the rewriting namers
section below).

If the name matches the pattern in the config, it will be replaced by the
name in the config.  Additionally, any variables in the pattern will capture
the value of the matching path segment and may be used in the final name.

Key     | Default Value    | Description
------- | ---------------- | -----------
prefix  | `io.l5d.rewrite` | Resolves names with `/#/<prefix>`.
pattern | _required_       | If the name matches this prefix, replace it with the name configured in the `name` parameter.  Wildcards and variable capture are allowed (see: `io.buoyant.namer.util.PathMatcher`).
name    | _required_       | The replacement name.  Variables captured in the pattern may be used in this string.

### Rewrite Path Parameters

> Dtab Path Format

```yaml
/#/<prefix> [/ *name ]
```

Key    | Required | Description
------ | -------- | -----------
prefix | yes      | Tells linkerd to resolve the request path using the rewrite namer.
name   | yes      | Attempt to match this name against the pattern and replace it with the configured name.

<a name="rewritingNamers"></a>
## Rewriting Namers

In addition to service discovery namers, linkerd supplies a number of utility
namers. These namers assist in path rewriting when the transformation is more
complicated than just prefix substitution. They are prefixed with `/$/` instead
of `/#/`, and can be used without explicitly adding them to the
[`namers`](#namers-and-service-discovery) section of the config.

### domainToPathPfx

```
/marathonId => /#/io.l5d.marathon;
/host       => /$/io.buoyant.http.domainToPathPfx/marathonId;
/http/1.1/* => /host;
```

> Dtab Path Format

```yaml
/$/io.buoyant.http.domainToPathPfx/<prefix>/<host>
```

Rewrites the path's prefix with `<prefix>` first, followed by each subdomain of
`<host>` separated and in reverse order.

For example,
`/$/io.buoyant.http.domainToPathPfx/pfx/foo.buoyant.io/resource/name` would be
rewritten to `/pfx/io/buoyant/foo/resource/name`.

### subdomainOfPfx

```
/consulSvc  => /#/io.l5d.consul/.local
/host       => /$/io.buoyant.http.subdomainOfPfx/service.consul/consulSvc;
/http/1.1/* => /host;
```

> Dtab Path Format

```yaml
/$/io.buoyant.http.subdomainOfPfx/<domain>/<prefix>/<host>
```

Rewrites the path's prefix with `<prefix>` first, followed by `<host>` with the
`<domain>` dropped.

For example,
`/$/io.buoyant.http.subdomainOfPfx/buoyant.io/pfx/foo.buoyant.io/resource/name`
would be rewritten to `/pfx/foo/resource/name`
