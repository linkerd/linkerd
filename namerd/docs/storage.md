# Storage

A storage object configures the namerd dtabStore which stores and retrieves
dtabs. This object supports the following params:

<aside class="notice">
These parameters are available to the storage regardless of kind. Storage may also have kind-specific parameters.
</aside>

Key | Default Value | Description
--- | ------------- | -----------
kind | _required_ | Either `io.l5d.inMemory`, `io.l5d.k8s`, `io.l5d.zk`, `io.l5d.etcd` or `io.l5d.consul`.
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
host | `kubernetes.default.svc.cluster.local` | The location of the Kubernetes API.
port | `443` | The port used to connect to the Kubernetes API.
tls | `true` | Whether to connect to the Kubernetes API using TLS.
tlsWithoutValidation | `false` | Whether to disable certificate checking against the Kubernetes API. Meaningless if `tls` is false.
authTokenFile | no auth | The location of the token used to authenticate against the Kubernetes API, if any.
namespace | `default` | The Kubernetes namespace in which dtabs will be stored. This should usually be the same namespace in which namerd is running.

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
