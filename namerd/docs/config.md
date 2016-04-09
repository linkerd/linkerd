# Configuration

namerd's configuration is controlled via config file, which must be provided
as a command-line argument. It may be a local file path or `-` to
indicate that the configuration should be read from the standard input.

## File Format

The configuration may be specified as a JSON or YAML object, as described
below.

```yaml
storage:
  kind: io.buoyant.namerd.storage.inMemory
  namespaces:
    galaxyquest: |
      /host     => /io.l5d.fs;
      /method   => /$/io.buoyant.http.anyMethodPfx/host;
      /http/1.1 => /method;
namers:
- kind: io.l5d.fs
  rootDir: examples/disco
interfaces:
- kind: thriftNameInterpreter
  port: 4100
  ip: 0.0.0.0
  retryBaseSecs:  600
  retryJitterSecs: 60
- kind: httpController
  port: 4321
```

## Storage

All configurations must define a **storage** key which must be an object
configuring the namerd dtabStore which stores and retrieves dtabs. This object
supports the following params:

* *kind* -- Required.  One of the supported storage plugins, by
fully-qualified class name.

### InMemoryDtabStore

`io.buoyant.namerd.storage.inMemory`

Stores the dtab in memory.  Not suitable for production use.

* *namespaces* -- Optional.  A map of namespaces to corresponding dtabs.

### K8sDtabStore
*experimental*

`io.buoyant.namerd.storage.experimental.k8s`

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

### ZkDtabStore
*experimental*

`io.buoyant.namerd.storage.experimental.zk`

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

## Namers

Naming and service discovery are configured via the `namers` section of the
configuration file. In this file, `namers` is an array of objects each of which
must be a namer as defined [here](https://linkerd.io/doc/0.2.0/config/#service-discovery-and-naming).

## Interfaces

A top-level `interfaces` section controls the published network interfaces to namerd. It is a collection of
`interface` objects supporting the following params:

* *kind* -- Required. One of the supported interface plugins.
* *ip* -- Optional.  The local IP address on which to serve the namer interface
(defaults may be provided by plugins)
* *port* -- Optional.  The port number on which to server the namer interface.
(defaults may be provided by plugins)

### thriftInterpreterInterface

A read-only interface providing `NameInterpreter` functionality over the ThriftMux protocol.

* default *ip*: 0.0.0.0 (wildcard)
* default *port*: 4100
* *retryBaseSecs* -- Optional. Base number of seconds to tell clients to wait
before retrying after an error.  (default: 600)
* *retryJitterSecs* -- Optional.  Maximum number of seconds to jitter retry
time by.  (default: 60)

### httpControllerInterface

A read-write HTTP interface to the `storage`.

* default *ip*: loopback
* default *port*: 4180
