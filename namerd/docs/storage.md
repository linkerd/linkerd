# Storage

A storage object configures the Namerd dtabStore which stores and retrieves
dtabs. This object supports the following parameters:

<aside class="notice">
These parameters are available to the storage regardless of kind. Storage may also have kind-specific parameters.
</aside>

Key | Default Value | Description
--- | ------------- | -----------
kind | _required_ | Either [`io.l5d.inMemory`](#in-memory), [`io.l5d.k8s`](#kubernetes), [`io.l5d.etcd`](#etcd) or [`io.l5d.consul`](#consul).
experimental | `false` | Set this to `true` to enable the storage if it is experimental.

## In Memory

kind: `io.l5d.inMemory`

Stores the dtab in memory.  Not suitable for production use.

Key | Default Value | Description
--- | ------------- | -----------
namespaces | empty map | A map of namespaces to corresponding dtabs.

## Kubernetes

kind: `io.l5d.k8s`

Stores the dtab with the Kubernetes master via the ThirdPartyResource APIs. Requires a cluster
running Kubernetes 1.2+ with the ThirdPartyResource feature enabled.

Key | Default Value | Description
--- | ------------- | -----------
host | `localhost` | The Kubernetes master host.
port | `8001` | The Kubernetes master port.
namespace | `default` | The Kubernetes namespace in which dtabs will be stored. This should usually be the same namespace in which Namerd is running.

<aside class="notice">
The Kubernetes storage plugin does not support TLS.  Instead, you should run `kubectl proxy` on each host
which will create a local proxy for securely talking to the Kubernetes cluster API. See (the k8s guide)[https://linkerd.io/doc/latest/k8s/] for more information.
</aside>
<p></p>
<aside class="notice">
The "ThirdPartyResource" resource has been deprecated in favor of a "CustomResourceDefinition" resource in Kubernetes 1.7 and has officially been removed in Kubernetes 1.8+.
To learn more about how to migrate existing third party resources to Custom Resource Definitions (CRD) <a href="https://kubernetes.io/docs/tasks/access-kubernetes-api/migrate-third-party-resource">See this guide.</a>
</aside>

### How to check if ThirdPartyResource is enabled (for Kubernetes v1.7 and below)

1. Open `extensions/v1beta1` api - `https://<k8s-cluster-host>/apis/extensions/v1beta1`.
2. Check that kind `ThirdPartyResource` exists in the response.

> Example ThirdPartyResource response

```json
{
  "kind": "APIResourceList",
  "groupVersion": "extensions/v1beta1",
  "resources": [
    {
      "name": "thirdpartyresources",
      "namespaced": false,
      "kind": "ThirdPartyResource"
    }
  ]
}
```

**Example of configuration for ThirdPartyResource in Kubernetes**

Use this configuration to create the ThirdPartyResource if it does not exist.

```yaml
metadata:
  name: d-tab.l5d.io # the hyphen is required by the Kubernetes API. This will be converted to the CamelCase name "DTab".
apiVersion: extensions/v1beta1
kind: ThirdPartyResource
description: stores dtabs used by Buoyant's `namerd` service
versions:
  - name: v1alpha1 # Do not change this value as it hardcoded in Namerd and doesn't work with other value.
```

### How to check if CustomResourceDefinition is enabled (for Kubernetes v1.8+)

1. Open `apiextensions.k8s.io/v1beta1` api - `https://<k8s-cluster-host>/apis/apiextensions.k8s.io/v1beta1`.
2. Check that kind `CustomResourceDefinition` exists in the response.

> Example CustomResourceDefinition response

```json
{
  "kind": "APIResourceList",
  "apiVersion": "v1",
  "groupVersion": "apiextensions.k8s.io/v1beta1",
  "resources": [
  {
    "name": "customresourcedefinitions",
    "singularName": "",
    "namespaced": false,
    "kind": "CustomResourceDefinition"
  }
  ]
}
```

**Example of configuration for CustomResourceDefinition in Kubernetes**

To create a new CustomResourceDefinition if using Kubernetes 1.8 and greater, use this configuration.

```yaml
kind: CustomResourceDefinition
apiVersion: apiextensions.k8s.io/v1beta1
metadata:
  name: dtabs.l5d.io
spec:
  scope: Namespaced
  group: l5d.io
  version: v1alpha1
  names:
    kind: DTab
    plural: dtabs
    singular: dtab
```

For a complete example configuration for `Namerd` configured with `k8s` storage,
see the [linkerd-examples](https://github.com/linkerd/linkerd-examples/blob/master/k8s-daemonset/k8s/namerd.yml) repo.

## Etcd

kind: `io.l5d.etcd`

Stores the dtab in etcd.

Key | Default Value | Description
--- | ------------- | -----------
experimental | _required_ | Because this storage is still considered experimental, you must set this to `true` to use it.
host | `localhost` | The location of the etcd API.
port | `2379` | The port used to connect to the etcd API.
pathPrefix | `/namerd/dtabs` | The key path under which dtabs should be stored.
tls | no tls | Use TLS encryption for connections to etcd. See [Namer TLS](#namer-tls).

## Consul

kind: `io.l5d.consul`

Stores the dtab in Consul KV storage.

The current state of Consul stored dtabs can be viewed at the
admin endpoint: `/storage/<pathPrefix>.json`.

Key | Default Value | Description
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

### Namer TLS

> Linkerd supports encrypted communication via TLS to `io.l5d.consul` and `io.l5d.etcd` namer backends.

```yaml
namers:
- kind: ...
  host: ...
  tls:
    disableValidation: false
    commonName: consul.io
    trustCertsBundle: /certificates/cacert.pem
    clientAuth:
      certPath: /certificates/cert.pem
      keyPath: /certificates/key.pem
```

A TLS object describes how Linkerd should use TLS when sending requests to Consul agent.

Key               | Default Value                              | Description
----------------- | ------------------------------------------ | -----------
disableValidation | false                                      | Enable this to skip hostname validation (unsafe). Setting `disableValidation: true` is incompatible with `clientAuth`.
commonName        | _required_ unless disableValidation is set | The common name to use for all TLS requests.
trustCerts        | empty list                                 | A list of file paths of CA certs to use for common name validation (deprecated, please use trustCertsBundle).
trustCertsBundle  | empty                                      | A file path of CA certs bundle to use for common name validation.
clientAuth        | none                                       | A client auth object used to sign requests.

If present, a clientAuth object must contain two properties:

Key      | Default Value | Description
---------|---------------|-------------
certPath | _required_    | File path to the TLS certificate file.
keyPath  | _required_    | File path to the TLS key file.  Must be in PKCS#8 format.

<aside class="warning">
Setting `disableValidation: true` will force the use of the JDK SSL provider which does not support client auth. Therefore, `disableValidation: true` and `clientAuth` are incompatible.
</aside>
