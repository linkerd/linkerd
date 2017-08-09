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
kind | _required_ | Either [`io.l5d.fs`](#file-based-service-discovery), [`io.l5d.serversets`](#zookeeper-serversets-service-discovery), [`io.l5d.consul`](#consul-service-discovery), [`io.l5d.k8s`](#kubernetes-service-discovery), [`io.l5d.marathon`](#marathon-service-discovery), [`io.l5d.zkLeader`](#zookeeper-leader), [`io.l5d.curator`](#curator), or [`io.l5d.rewrite`](#rewrite).
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
  /svc => /#/io.l5d.fs
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
192.0.2.210 8080 * 2.0
```

<aside class="warning">
Due to the implmentation of file watches in Java, this namer consumes a high
amount of CPU and is not suitable for production use.
</aside>

linkerd ships with a simple file-based service discovery mechanism called the
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
  /svc => /#/io.l5d.serversets/discovery/prod;
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
  /svc => /#/io.l5d.consul/dc1/prod;
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
preferServiceAddress | `true` | If `true` use the service address if defined and default to the node address. If `false` always use the node address.

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
  /svc => /#/io.l5d.k8s/prod/http;
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
which will create a local proxy for securely talking to the Kubernetes cluster API. See [the k8s guide](https://linkerd.io/doc/latest/k8s/) for more information.
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
port-name | yes | The port name or port number.
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
  /svc => /#/io.l5d.k8s.external/prod/http;
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
which will create a local proxy for securely talking to the Kubernetes cluster API. See [the k8s guide](https://linkerd.io/doc/latest/k8s/) for more information.
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

### K8s Namespaced Configuration

> Example usage of the namespaced namer that routes traffic to services within the current namespace

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: l5d-config
data:
  config.yaml: |-
    namers:
    - kind: io.l5d.k8s.ns
      host: localhost
      port: 8001
      envVar: MY_POD_NAMESPACE

    routers:
    - protocol: http
      dtab: |
        /svc => /#/io.l5d.k8s.ns/admin;
      servers:
      - port: 4140
        ip: 0.0.0.0

---
apiVersion: extensions/v1beta1
kind: Deployment
metadata:
  labels:
    app: l5d
  name: l5d
spec:
  template:
    metadata:
      labels:
        app: l5d
    spec:
      volumes:
      - name: l5d-config
        configMap:
          name: "l5d-config"
      containers:
      - name: l5d
        image: buoyantio/linkerd:latest
        # Use the downward api to populate an environment variable
        env:
        - name: MY_POD_NAMESPACE
          valueFrom:
            fieldRef:
              fieldPath: metadata.namespace
        args:
        - /io.buoyant/linkerd/config/config.yaml
        ports:
        - name: http
          containerPort: 4140
        - name: admin
          containerPort: 9990
        volumeMounts:
        - name: "l5d-config"
          mountPath: "/io.buoyant/linkerd/config"
          readOnly: true

      - name: kubectl
        image: buoyantio/kubectl:v1.6.2
        args: ["proxy", "-p", "8001"]
---
apiVersion: v1
kind: Service
metadata:
  name: l5d
spec:
  selector:
    app: l5d
  type: LoadBalancer
  ports:
  - name: http
    port: 4140
  - name: admin
    port: 9990

```


The [Kubernetes](https://k8s.io/) Namespaced namer scopes service discovery to
the current namespace, as provided by the
[Kubernetes downward api](https://kubernetes.io/docs/tasks/configure-pod-container/environment-variable-expose-pod-information/#the-downward-api).

Key | Default Value | Description
--- | ------------- | -----------
prefix | `io.l5d.k8s.ns` | Resolves names with `/#/<prefix>`.
envVar | `POD_NAMESPACE` | Environment variable that contains the namespace name.
host | `localhost` | The Kubernetes master host.
port | `8001` | The Kubernetes master port.
labelSelector | none | The key of the label to filter services.

<aside class="notice">
The Kubernetes namer does not support TLS.  Instead, you should run `kubectl proxy` on each host
which will create a local proxy for securely talking to the Kubernetes cluster API. See [the k8s guide](https://linkerd.io/doc/latest/k8s/) for more information.
</aside>

### K8s Namespaced Path Parameters

> Dtab Path Format

```yaml
/#/<prefix>/<port-name>/<svc-name>[/<label-value>]
```

Key | Required | Description
--- | -------- | -----------
prefix | yes | Tells linkerd to resolve the request path using the k8s external namer.
port-name | yes | The port name.
svc-name | yes | The name of the service.
label-value | yes if `labelSelector` is defined | The label value used to filter services.

### Istio Configuration

> Configure an Istio namer

```yaml
namers:
- kind: io.l5d.k8s.istio
  experimental: true
  host: istio-manager.default.svc.cluster.local
  port: 8080
```

> Then reference the namer in the dtab to use it:

```
dtab: |
  /svc/reviews => /#/io.l5d.k8s.istio/version:v1/http/reviews;
```

The [Istio](https://istio.io/) namer uses the Istio-Manager's Service Discovery Service to lookup
the endpoints for a given namespace, port, service, and list of label selectors.

Key | Default Value | Description
--- | ------------- | -----------
prefix | `io.l5d.k8s.istio` | Resolves names with `/#/<prefix>`.
experimental | _required_ | Because this namer is still considered experimental, you must set this to `true` to use it.
host | `istio-manager.default.svc.cluster.local` | The host of the Istio-Manager.
port | `8080` | The port of the Istio-Manager.

### Istio Path Parameters

> Dtab Path Format

```yaml
/#/<prefix>/<cluster>/<labels>/<port-name>
```

Key | Required | Description
--- | -------- | -----------
prefix | yes | Tells linkerd to resolve the request path using the Istio namer.
port-name | yes | The port name.
cluster | yes | The fully qualified name of the service.
labels | yes | A `::` delimited list of `label:value` pairs.  Only endpoints that match all of these label selectors will be returned.

<a name="marathon"></a>
## Marathon service discovery

kind: `io.l5d.marathon`

### Marathon Configuration

> Configure a marathon namer

```yaml
namers:
- kind:           io.l5d.marathon
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
  /svc => /host;
```

linkerd provides support for service discovery via
[Marathon](https://mesosphere.github.io/marathon/).

Key | Default Value | Description
--- | ------------- | -----------
prefix | `io.l5d.marathon` | Resolves names with `/#/<prefix>`.
host | `marathon.mesos` | The Marathon master host.
port | `80` | The Marathon master port.
uriPrefix | none | The Marathon API prefix. This prefix depends on your Marathon configuration. For example, running Marathon locally, the API is available at `localhost:8080/v2/`, while the default setup on AWS/DCOS is `$(dcos config show core.dcos_url)/marathon/v2/apps`.
ttlMs | `5000` | The polling interval in milliseconds against the Marathon API.
useHealthCheck | `false` | If `true`, exclude app instances that are failing Marathon health checks. Even if `false`, linkerd's built-in resiliency algorithms will still apply.
tls | no tls | The Marathon namer will make requests to Marathon/DCOS using TLS if this parameter is provided. This is useful when DC/OS is run in [strict](https://docs.mesosphere.com/latest/security/#security-modes) security mode. It must be a [client TLS](#client-tls) object. Note that the `clientAuth` config value will be unused, as DC/OS does not use mutual TLS.

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

> Example DCOS environment variable

```json
{
  "login_endpoint": "https://leader.mesos/acs/api/v1/auth/login",
  "private_key": "<private-key-value>",
  "scheme": "RS256",
  "uid": "service-acct"
}
```

> Example basic HTTP authentication variable

```bash
dXNlcm5hbWU6cGFzc3dvcmQ=
```

The Marathon namer supports loading authentication data from an environment variable for DCOS private key in the `DCOS_SERVICE_ACCOUNT_CREDENTIAL` variable and standalone Marathon basic HTTP authentication in the `MARATHON_HTTP_AUTH_CREDENTIAL` environment variable. If both are provided the `DCOS_SERVICE_ACCOUNT_CREDENTIAL` takes precedence.

Basic authentication token is base64 encoded and should not include the `Basic` prefix, only in the format `username:password`.

Further reading:

* [Mesosphere DCOS Authentication Docs](https://docs.mesosphere.com/1.8/administration/id-and-access-mgt/service-auth/custom-service-auth/)
* [Marathon basic HTTP Authentication Docs](https://mesosphere.github.io/marathon/docs/ssl-basic-access-authentication.html#enabling-basic-access-authentication)
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
  prefix: /rewrite
  pattern: "/{service}/api"
  name: "/srv/{service}"
```

> Then reference the namer in the dtab to use it:

```
dtab: |
  /svc => /#/rewrite
```

A namer that completely rewrites a path.  This is useful for doing arbitrary
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
prefix  | _required_       | Resolves names with `/#/<prefix>`.
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
/svc => /host;
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
/svc => /host;
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

### hostportPfx

```
/ip-hostport => /$/inet;
/svc         => /$/io.buoyant.hostportPfx/ip-hostport;
```

> Dtab Path Format

```yaml
/$/io.buoyant.hostportPfx/<prefix>/<host>:<port>/etc
```

Rewrites a name of the form "host:ip" as a path with host followed by ip. Does
not support IPv6 host IPs (because IPv6 notation doesn't work in Paths as-is
due to bracket characters).

For example,
`/$/io.buoyant.hostportPfx/pfx/host:port/etc`
would be rewritten to `/pfx/host/port/etc`.

### porthostPfx

```
/k8s-porthost => /#/io.l5d.k8s/default;
/svc          => /$/io.buoyant.porthostPfx/k8s-porthost;
```

> Dtab Path Format

```yaml
/$/io.buoyant.porthostPfx/<prefix>/<host>:<port>/etc
```

Rewrites a name of the form "host:ip" as a path with ip followed by host. Does
not support IPv6 host IPs (because IPv6 notation doesn't work in Paths as-is
due to bracket characters).

For example,
`/$/io.buoyant.porthostPfx/pfx/host:port/etc`
would be rewritten to `/pfx/port/host/etc`.
