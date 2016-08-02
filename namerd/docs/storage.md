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
* *port* -- Optional. The port used to connect to the etcd API.  (default: 8500)
* *pathPrefix* -- Optional.  The key path under which dtabs should be stored.  (default: "/namerd/dtabs")
* *recursive* -- Optional.  Whether to use recursive strategy to fetch namespaces.  (default: "false")

In `recursive` mode the storage will look not only at keys that are immediate children of the `pathPrefix`
but it will traverse the whole tree beneath recursively. The resulting dtab is composed from all non-empty
valid values beneath the given namespace (sorted in alphabetical order of keys).

This may be very useful when you need to modify your dtab in runtime in automated manner and you don't want
to bother with dealing with dtabs syntax - you can just put mutable parts into separate keys and update them as needed.  

Although `recursive` strategy may sound as something complicated at first it's actually very simple. Let's just
look at an example and see how it will work in `recusrive` and non-`recursive` modes. 

Let's assume that we configured storage `pathPrefix=/namerd/dtabs` and the KV in Consul is as below:

```
namerd/
       dtabs/
             sample = "/foo=>/bar"
             nested/
                    example = "/fiz=>/baz"
                    broken = "invalid dtab"
             mydtab = "/1st=>line"
             mydtab/
                    a = "/2nd=>/line"
                    a/
                      a = "/3rd=>/line"
                      b = "/4th=>/line"
                    b = "/5th=>/line"
                    c/
                      1 = "/6th=>/line"
                    empty = ""
```

If we list namespaces in non-recursive mode, we will see just 2 of them (with their Dtabs given in parenthesis):
* `sample` (`"/foo=>/bar"`)
* `mydtab` (`"/1st=>line"`)

On the other hand, in recursive mode we will see a slightly bigger list of namespaces:
* `sample` (`"/foo=>/bar"`)
* `nested/` (`"/fiz=>/baz"`)
* `nested/example` (`"/fiz=>/baz"`)
* `nested/broken` (`""`)
* `mydtab` (`"/1st=>line"`)
* `mydtab/` (`/1st=>/line;/2nd=>/line;/3rd=>/line;/4th=>/line;/5th=>/line;/6th=>/line;`)
* `mydtab/a` (`"/2nd=>/line"`)
* `mydtab/a/` (`"/3rd=>/line;/4th=>/line"`)
* `mydtab/a/a` (`"/3rd=>/line"`)
* `mydtab/a/b` (`"/4th=>/line"`)
* `mydtab/b` (`"/5th=>/line"`)
* `mydtab/c/` (`"/6th=>/line"`)
* `mydtab/c/1` (`"/6th=>/line"`)
* `mydtab/empty` (`""`)

So the rule is simple: everything that doesn't end with `/` is a key that can has some value.
Everything that ends with `/` is a 'virtual namespace' that aggregates other keys.
 
With regards to `namerd` API there is only 1 restriction - you cannot create/update 'virtual namespaces'.
But you can read and delete them (removal of a 'virtual namespace' will recursively remove everything beneath it).
