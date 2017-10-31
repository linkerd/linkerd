# Storage

A storage object configures the namerd dtabStore which stores and retrieves
dtabs. This object supports the following parameters:

<aside class="notice">
These parameters are available to the storage regardless of kind. Storage may also have kind-specific parameters.
</aside>

Key | Default Value | Description
--- | ------------- | -----------
kind | _required_ | Either [`io.l5d.inMemory`](#in-memory), [`io.l5d.k8s`](#kubernetes), [`io.l5d.zk`](#zookeeper), [`io.l5d.etcd`](#etcd) or [`io.l5d.consul`](#consul).
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
namespace | `default` | The Kubernetes namespace in which dtabs will be stored. This should usually be the same namespace in which namerd is running.

<aside class="notice">
The Kubernetes storage plugin does not support TLS.  Instead, you should run `kubectl proxy` on each host
which will create a local proxy for securely talking to the Kubernetes cluster API. See (the k8s guide)[https://linkerd.io/doc/latest/k8s/] for more information.
</aside>


**Deprecation Notice**:
As of Kubernetes 1.8+, The ThirdPartyResource API has been [deprecated](https://kubernetes.io/docs/tasks/access-kubernetes-api/extend-api-third-party-resource/) in favor of CustomResourceDefinitions
API. Kubernetes 1.7 supports both API to help migrate ThirdPartyResource objects to CustomResourceDefinition objects. More details on how to migrate to the new API in Kubernetes 1.8+ can be found
[here](https://kubernetes.io/docs/tasks/access-kubernetes-api/migrate-third-party-resource/). 


**How to check ThirdPartyResource is enabled**

1. Open `extensions/v1beta1` api - `https://<k8s-cluster-host>/apis/extensions/v1beta1`.
2. Check that kind `ThirdPartyResource` exists in the response.

> Example ThirdPartyResource response

```yaml
{
  "kind": "APIResourceList",
  "groupVersion": "extensions/v1beta1",
  "resources": [
    ...
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

For a complete example configuration for `Namerd` configured with `k8s` storage,
see the [linkerd-examples](https://github.com/linkerd/linkerd-examples/blob/master/k8s-daemonset/k8s/namerd.yml) repo.

## ZooKeeper

kind: `io.l5d.zk`

Stores the dtab in ZooKeeper.

Key | Default Value | Description
--- | ------------- | -----------
zkAddrs | _required_ | A list of ZooKeeper addresses, each of which have `host` and `port` parameters.
pathPrefix | `/dtabs` | The ZooKeeper path under which dtabs should be stored.
sessionTimeoutMs | `10000` | ZooKeeper session timeout in milliseconds.
authInfo | no auth when logging | Configures the authentication information to use when logging. See [authInfo](#authInfo).
acls | an empty list | A list of ACLs to set on each dtab znode created. See [acls](#acls).

### authInfo

Key | Default Value | Description
--- | ------------- | -----------
scheme | _required_ | The ZooKeeper auth scheme to use.
auth | _required_ | The ZooKeeper auth value to use.

### acls

Key | Default Value | Description
--- | ------------- | -----------
scheme | _required_ | The ACL auth scheme to use.
id | _required_ | The ACL id to use.
perms | _required_ | A subset of the string "crwda" representing the permissions of this ACL. The characters represent create, read, write, delete, and admin, respectively.

## Etcd

kind: `io.l5d.etcd`

Stores the dtab in Etcd.

Key | Default Value | Description
--- | ------------- | -----------
experimental | _required_ | Because this storage is still considered experimental, you must set this to `true` to use it.
host | `localhost` | The location of the etcd API.
port | `2379` | The port used to connect to the etcd API.
pathPrefix | `/namerd/dtabs` | The key path under which dtabs should be stored.

## Consul

kind: `io.l5d.consul`

Stores the dtab in Consul KV storage.

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

