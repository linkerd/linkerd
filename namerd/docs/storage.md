# Storage

A storage object configures the namerd dtabStore which stores and retrieves
dtabs. This object supports the following params:

* *kind* -- The name of the storage plugin.
* *sotrage-specific parameters*.

## io.buoyant.namerd.storage.inMemory

Stores the dtab in memory.  Not suitable for production use.

* *namespaces* -- Optional.  A map of namespaces to corresponding dtabs.

## io.buoyant.namerd.storage.experimental.k8s
*experimental*

Stores the dtab with the Kubernetes master via the ThirdPartyResource APIs. Requires a cluster
running Kubernetes 1.2+ with the ThirdPartyResource feature enabled.

* *host* -- Optional. The location of the Kubernetes API. (default: "kubernetes.default.svc.cluster.local")
* *port* -- Optional. The port used to connect to the Kubernetes API. (default: 443)
* *tls*  -- Optional. Whether to connect to the Kubernetes API using TLS. (default: true)
* *tlsWithoutValidation* -- Optional. Whether to disable certificate checking against the Kubernetes
  API. Meaningless if *tls* is false. (default: false)
* *authTokenFile* -- Optional. The location of the token used to authenticate against the Kubernetes
  API, if any. (default: no authentication)
* *namespace* The Kubernetes namespace in which dtabs will be stored. This should usually be the
  same namespace in which namerd is running. (default: "default")

## io.buoyant.namerd.storage.experimental.zk

Stores the dtab in ZooKeeper.  Supports the following options

* *hosts* -- Required.  A list of hosts where ZooKeeper is running.
* *pathPrefix* -- Optional.  The ZooKeeper path under which dtabs should be stored.  (default:
"/dtabs")
* *sessionTimeoutMs* -- Optional.  ZooKeeper session timeout in milliseconds.
(default: 10000)
* *authInfo* -- Optional.  An object containing the authentication information to use when logging
in ZooKeeper:
  * *scheme* -- Required.  The ZooKeeper auth scheme to use.
  * *auth* -- Required.  The ZooKeeper auth value to use.
* *acls* -- Optional.  A list of ACLs to set on each dtab znode created.  Each ACL is an object
containing:
  * *scheme* -- Required.  The ACL auth scheme to use.
  * *id* -- Required.  The ACL id to use.
  * *perms* -- Required.  A subset of the string "crwda" representing the permissions of this ACL.
  The characters represent create, read, write, delete, and admin, respectively.

## io.buoyant.namerd.storage.experimental.etcd

Stores the dtab in Etcd.  Supports the following options

* *host* -- Optional. The location of the etcd API.  (default: localhost)
* *port* -- Optional. The port used to connect to the etcd API.  (default: 2379)
* *pathPrefix* -- Optional.  The key path under which dtabs should be stored.  (default: "/namerd/dtabs")