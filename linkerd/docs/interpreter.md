# Interpreter

> Example Interpreter Configuration

```yaml
routers:
- ...
  interpreter:
    kind: io.l5d.namerd
    dst: /$/inet/1.2.3.4/4180
```

An interpreter determines how names are resolved.

<aside class="notice">
These parameters are available to the identifier regardless of kind. Identifiers may also have kind-specific parameters.
</aside>

Key | Default Value | Description
--- | ------------- | -----------
kind | `default` | Either [`default`](#default), [`io.l5d.namerd`](#namerd-thrift), [`io.l5d.namerd.http`](#namerd-http), [`io.l5d.mesh`](#namerd-mesh), [`io.l5d.fs`](#file-system), [`io.l5d.consul.interpreter`](#consul-interpreter) or [`io.l5d.k8s.configMap`](#kubernetes-configmap).
transformers | No transformers | A list of [transformers](#transformer) to apply to the resolved addresses.

## Default

kind: `default`

The default interpreter resolves names via the configured
[`namers`](#namers), with a fallback to the default Finagle
`Namer.Global` that handles paths of the form `/$/`.

## Namerd thrift

kind: `io.l5d.namerd`

The Namerd thrift interpreter offloads the responsibilities of name resolution
to the Namerd service.  Any namers configured in this Linkerd are not used.  The
interpreter uses Namerd's long-poll thrift interface
(`io.l5d.thriftNameInterpreter`). Note that the protocol that the interpreter
uses to talk to Namerd is unrelated to the protocols of Linkerd's routers.

The internal state of the Namerd interpreter can be viewed at the
admin endpoint: `/<router>/interpreter_state/io.l5d.namerd/<namespace>.json`.

Key | Default Value | Description
--- | ------------- | -----------
dst | _required_ | A Finagle path locating the Namerd service.
namespace | `default` | The name of the Namerd dtab to use.
retry | see [Namerd retry](#namerd-retry) | An object configuring retry backoffs for requests to Namerd.
tls | no tls | Requests to Namerd will be made using TLS if this parameter is provided.  It must be a [Namerd client TLS](#namerd-client-tls) object.
failureThreshold | no failureThreshold | Sets the [failure threshold](#failure-threshold) used by Linkerd's threshold failure detector to gauge a Namerd instance's health

### Failure Threshold

Linkerd uses a [Threshold Failure Detector](https://github.com/twitter/finagle/blob/master/finagle-core/src/main/scala/com/twitter/finagle/liveness/ThresholdFailureDetector.scala)
to determine the health of the connection to a Namerd instance. Linkerd sends pings to Namerd periodically and evaluates the health of Namerd based on a set number of ping latencies

Key | Default Value | Description
--- | ------------- | -----------
minPeriodMs | 5000 | The period between session pings to Namerd
closeTimeoutMs | 4000 | Timeout for a session ping's response before Linkerd terminates a session

### Namerd retry

Key | Default Value | Description
--- | ------------- | -----------
baseSeconds | 5 seconds | The base number of seconds to wait before retrying.
maxSeconds | 10 minutes | The maximum number of seconds to wait before retrying.

### Namerd client tls

Key | Default Value | Description
--- | ------------- | -----------
commonName | _required_ | The common name to use for Namerd requests.
caCert | N/A | The path to the CA cert used for common name validation.

## Namerd http

kind: `io.l5d.namerd.http`

The Namerd http interpreter offloads the responsibilities of name resolution to
the Namerd service.  Any namers configured in this Linkerd are not used.  The
interpreter uses Namerd's HTTP streaming interface (`io.l5d.httpController`).
Note that the protocol that the interpreter uses to talk to Namerd is unrelated
to the protocols of Linkerd's routers.

Key | Default Value | Description
--- | ------------- | -----------
experimental | _required_ | Because the http interpreter is still considered experimental, you must set this to `true` to use it.
dst | _required_ | A Finagle path locating the Namerd service.
namespace | `default` | The name of the Namerd dtab to use.
retry | see [Namerd retry](#namerd-retry) | An object configuring retry backoffs for requests to Namerd.
tls | no tls | Requests to Namerd will be made using TLS if this parameter is provided.  It must be a [client TLS](#client-tls) object.

## Namerd mesh

kind: `io.l5d.mesh`

The Namerd mesh interpreter offloads the responsibilities of name resolution to
the Namerd service.  Any namers configured in this Linkerd are not used.  The
interpreter uses Namerd's gRPC mesh interface (`io.l5d.mesh`). Note that the
protocol that the interpreter uses to talk to Namerd is unrelated to the
protocols of Linkerd's routers.

The internal state of the Namerd interpreter can be viewed at the admin endpoint:
`/<router>/interpreter_state/io.l5d.mesh/<root>.json`.

Key | Default Value | Description
--- | ------------- | -----------
dst | _required_ | A Finagle path locating the Namerd service.
root | `/default` | A single-element Finagle path representing the Namerd namespace.
retry | see [Namerd retry](#namerd-retry) | An object configuring retry backoffs for requests to Namerd.
tls | no tls | Requests to Namerd will be made using TLS if this parameter is provided.  It must be a [client TLS](#client-tls) object.

## File-System

kind: `io.l5d.fs`

The file-system interpreter resolves names via the configured
[`namers`](#namers), just like the default interpreter, but also uses
a dtab read from a file on the local file-system.  The specified file is watched
for changes so that the dtab may be edited live.

Key | Default Value | Description
--- | ------------- | -----------
dtabFile | _required_ | The file-system path to a file containing a dtab.

## Kubernetes ConfigMap

kind: `io.l5d.k8s.configMap`

The Kubernetes ConfigMap interpreter resolves names via the configured
[`namers`](#namers), just like the default interpreter, but also uses
a dtab read from a [ConfigMap](https://kubernetes.io/docs/tasks/configure-pod-container/configmap/#understanding-configmaps) using the Kubernetes API. The specified ConfigMap is watched for changes, as in the [file-system interpreter](#file-system).

> Example configuration

```yaml
routers:
- ...
  interpreter:
    kind: io.l5d.k8s.configMap
    experimental: true
    name: dtabs
    filename: my-dtab
```


Key | Default Value | Description
--- | ------------- | -----------
experimental | _required_ | Because the ConfigMap interpreter is still considered experimental, you must set this to `true` to use it.
name | _required_ | The name of the ConfigMap object containing the dtab
filename | _required_ | The ConfigMap key corresponding to the desired dtab
host | `localhost` | The Kubernetes master host.
port | `8001` | The Kubernetes master port.
namespace | `default` | The Kubernetes [namespace](https://kubernetes.io/docs/concepts/overview/working-with-objects/namespaces/) containing the ConfigMap

## Consul Interpreter

The Consul Interpreter uses dtabs read from a Consul KV store. The interpreter watches for
changes to dtabs stored at the specified `pathPrefix`.

The current state of Consul stored dtabs can be viewed at the
admin endpoint: `/interpreter_state/io.l5d.consul.interpreter.json`.

> Example configuration

```yaml
routers:
- ...
  interpreter:
    kind: io.l5d.consul.interpreter
    host: localhost
    port: 8500
    namespace: default

``` 

Key | Default value | Description
--- | ------------- | -----------
host | `localhost` | The location of the consul API.
port | `8500` | The port used to connect to the consul API.
pathPrefix | `/namerd/dtabs` | The key path under which dtabs should be stored.
token | no auth | The auth token to use when making API calls.
datacenter | uses agent's datacenter | The datacenter to forward requests to.
readConsistencyMode | `default` | Select between [Consul API consistency modes](https://www.consul.io/docs/agent/http.html) such as `default`, `stale` and `consistent` for reads.
writeConsistencyMode | `default` | Select between [Consul API consistency modes](https://www.consul.io/docs/agent/http.html) such as `default`, `stale` and `consistent` for writes.
failFast | `false` | If `false`, disable fail fast and failure accrual for Consul client. Keep it `false` when using a local agent but change it to `true` when talking directly to an HA Consul API.
backoff |  exponential backoff from 1ms to 1min | Object that determines which backoff algorithm should be used. See [retry backoff](https://linkerd.io/config/head/linkerd#retry-backoff-parameters)
tls | no tls | Use TLS during connection with Consul. see [Consul Encryption](https://www.consul.io/docs/agent/encryption.html) and [Namer TLS](#namer-tls).
