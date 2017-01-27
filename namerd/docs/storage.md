# Storage

A storage object configures the namerd dtabStore which stores and retrieves
dtabs. This object supports the following params:

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
experimental | _required_ | Because this storage is still considered experimental, you must set this to `true` to use it.
host | `localhost` | The Kubernetes master host.
port | `8001` | The Kubernetes master port.
namespace | `default` | The Kubernetes namespace in which dtabs will be stored. This should usually be the same namespace in which namerd is running.

<aside class="notice">
The Kubernetes storage plugin does not support TLS.  Instead, you should run `kubectl proxy` on each host
which will create a local proxy for securely talking to the Kubernetes cluster API. See (the k8s guide)[https://linkerd.io/doc/latest/k8s/] for more information.
</aside>

**How to check ThirdPartyResource is enabled**
    1. Open `extensions/v1beta1` api - `https://<k8s-cluster-host>/apis/extensions/v1beta1`.
    2. Check that kind `ThirdPartyResource` exists in response:

    ```
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

  ```yaml
  metadata:
    name: d-tab.l5d.io # the hyphen is required by the Kubernetes API. This will be converted to the CamelCase name "DTab".
  apiVersion: extensions/v1beta1
  kind: ThirdPartyResource
  description: stores dtabs used by Buoyant's `namerd` service
  versions:
    - name: v1alpha1 # Do not change this value as it hardcoded in Namerd and doesn't work with other value.
  ```
---
*Complete example of `Namerd` configuration with `k8s` storage and exposed 2 services for sync with `Linkerd` and `Namerd API`:*

```yaml
apiVersion: v1
kind: Service
metadata:
  name: namerd-sync
spec:
  selector:
    app: namerd
  ports:
  - name: sync
    port: 4100
---
apiVersion: v1
kind: Service
metadata:
  name: namerd-api
spec:
  selector:
    app: namerd
  ports:
  - name: api
    port: 4180
---
metadata:
  name: d-tab.l5d.io # the hyphen is required by the Kubernetes API. This will be converted to the CamelCase name "DTab".
apiVersion: extensions/v1beta1
kind: ThirdPartyResource
description: stores dtabs used by Buoyant's `namerd` service
versions:
  - name: v1alpha1 # Do not change this value as it hardcoded in Namerd and doesn't work with other value.
---
kind: ConfigMap
apiVersion: v1
metadata:
  name: namerd-config
data:
  config.yml: |-
    admin:
      ip: 127.0.0.1
      port: 9991
    storage:
      kind: io.l5d.k8s
      experimental: true
    namers:
      - kind: io.l5d.k8s
        experimental: true
        host: 127.0.0.1
        port: 8001
    interfaces:
      - kind: io.l5d.thriftNameInterpreter
        ip: 0.0.0.0
        port: 4100
      - kind: io.l5d.httpController
        ip: 0.0.0.0
        port: 4180
---
kind: ReplicationController
apiVersion: v1
metadata:
  name: namerd
spec:
  replicas: 1
  selector:
    app: namerd
  template:
    metadata:
      labels:
        app: namerd
    spec:
      dnsPolicy: ClusterFirst
      volumes:
        - name: namerd-config
          configMap:
            name: namerd-config
      containers:
        - name: namerd
          image: buoyantio/namerd:<version> # specify required version or remove to use the latest
          args:
            - /io.buoyant/namerd/config/config.yml
            - -com.twitter.finagle.tracing.debugTrace=true
            - -log.level=DEBUG
          imagePullPolicy: Always
          ports:
            - name: sync
              containerPort: 4100
            - name: api
              containerPort: 4180
          volumeMounts:
            - name: "namerd-config"
              mountPath: "/io.buoyant/namerd/config"
              readOnly: true
        - name: kubectl
          image: buoyantio/kubectl:<version> # specify required version or remove to use the latest
          args:
          - "proxy"
          - "-p"
          - "8001"
          imagePullPolicy: Always
```



## ZooKeeper

kind: `io.l5d.zk`

Stores the dtab in ZooKeeper.

Key | Default Value | Description
--- | ------------- | -----------
experimental | _required_ | Because this storage is still considered experimental, you must set this to `true` to use it.
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
experimental | _required_ | Because this storage is still considered experimental, you must set this to `true` to use it.
host | `localhost` | The location of the consul API.
port | `8500` | The port used to connect to the consul API.
pathPrefix | `/namerd/dtabs` | The key path under which dtabs should be stored.
token | no auth | The auth token to use when making API calls.
datacenter | uses agent's datacenter | The datacenter to forward requests to.
readConsistencyMode | `default` | Select between [Consul API consistency modes](https://www.consul.io/docs/agent/http.html) such as `default`, `stale` and `consistent` for reads.
writeConsistencyMode | `default` | Select between [Consul API consistency modes](https://www.consul.io/docs/agent/http.html) such as `default`, `stale` and `consistent` for writes.
failFast | `false` | If `false`, disable fail fast and failure accrual for Consul client. Keep it `false` when using a local agent but change it to `true` when talking directly to an HA Consul API.

