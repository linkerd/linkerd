# Storage

*(for the [storage](config.md#storage) key)*

A storage object configures the namerd dtabStore which stores and retrieves
dtabs. This object supports the following params:

* *kind* -- The name of the storage plugin.
* *experimental* -- Set this to `true` to enable the storage if it is experimental.
* *sotrage-specific parameters*.

## In Memory

`io.l5d.inMemory`

Stores the dtab in memory.  Not suitable for production use.

* *namespaces* -- Optional.  A map of namespaces to corresponding dtabs.

## Kubernetes

`io.l5d.k8s`

*experimental*

Stores the dtab with the Kubernetes master via the ThirdPartyResource APIs. 

**Prerequisites**  
1. Requires a cluster running Kubernetes 1.2+.  
2. `kubectl` client 1.3+ is installed.  
3. [ThirdPartyResource](http://kubernetes.io/docs/api-reference/extensions/v1beta1/definitions/#_v1beta1_thirdpartyresource) feature should be enabled in Kubernetes cluster.  
  1. **How to check ThirdPartyResource is enabled**
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

**Configuration**  

1. Define configuration of `ThirdPartyResource`.
  ```yaml
  metadata:
    name: d-tab.l5d.io # the hyphen is required by the Kubernetes API. This will be converted to the CamelCase name "DTab".
  apiVersion: extensions/v1beta1
  kind: ThirdPartyResource
  description: stores dtabs used by Buoyant's `namerd` service
  versions:
    - name: v1alpha1 # Do not change this value as it hardcoded in Namerd and doesn't work with other value.
  ```
  
2. Define configuration of `Namerd` with k8s cluster storage.
  * *experimental* -- Required. Has to be set to `true` as `k8s` storage is experimental feature right now.
  * *host* -- Optional. The location of the Kubernetes API. (default: "kubernetes.default.svc.cluster.local")
  * *port* -- Optional. The port used to connect to the Kubernetes API. (default: 443)
  * *tls*  -- Optional. Whether to connect to the Kubernetes API using TLS. (default: true)
  * *tlsWithoutValidation* -- Optional. Whether to disable certificate checking against the Kubernetes
  API. Meaningless if *tls* is false. (default: false)
  * *authTokenFile* -- Optional. The location of the token used to authenticate against the Kubernetes
  API, if any. (default: no authentication)
  * *namespace* The Kubernetes namespace in which dtabs will be stored. This should usually be the
  same namespace in which namerd is running. (default: "default")

*Example:*  
```yaml
  storage:
    kind: io.l5d.k8s
    experimental: true
```

*Complete example of `Namerd` configuration with `k8s` storage and exposed 2 serivces for sync with `Linkerd` and `Namerd API`:*  

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
          image: buoyantio/namerd:0.7.4
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
          image: buoyantio/kubectl:1.2.3
          args:
          - "proxy"
          - "-p"
          - "8001"
          imagePullPolicy: Always
```

## ZooKeeper

`io.l5d.zk`

*experimental*

Stores the dtab in ZooKeeper.  Supports the following options

* *zkAddrs* -- list of ZooKeeper addresses:
  * *host* --  the ZooKeeper host.
  * *port* --  the ZooKeeper port.
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

## Etcd

`io.l5d.etcd`

*experimental*

Stores the dtab in Etcd.  Supports the following options

* *host* -- Optional. The location of the etcd API.  (default: localhost)
* *port* -- Optional. The port used to connect to the etcd API.  (default: 2379)
* *pathPrefix* -- Optional.  The key path under which dtabs should be stored.  (default: "/namerd/dtabs")

## Consul

`io.l5d.consul`

*experimental*

Stores the dtab in Consul KV storage.  Supports the following options

* *host* -- Optional. The location of the etcd API.  (default: localhost)
* *port* -- Optional. The port used to connect to the consul API.  (default: 8500)
* *pathPrefix* -- Optional. The key path under which dtabs should be stored. (default: "/namerd/dtabs")
* *token* -- Optional. The auth token to use when making API calls.
* *datacenter* -- Optional. The datacenter to forward requests to. (default: not set, use agent's datacenter)
