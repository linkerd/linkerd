# Transformer

> Example Transformer Configuration

```yaml
routers:
- ...
  interpreter:
    ...
    transformers:
    - kind: io.l5d.localhost
```

Transformers perform a transformation on the addresses resolved by the
interpreter.  Transformations are applied sequentially in the order they appear.


Key | Default Value | Description
--- | ------------- | -----------
kind | _required_ | Either [`io.l5d.localhost`](#localhost), [`io.l5d.specificHost`](#specific-host), [`io.l5d.port`](#port), [`io.l5d.k8s.daemonset`](#daemonset-kubernetes), [`io.l5d.k8s.localnode`](#localnode-kubernetes), [`io.l5d.replace`](#replace), or [`io.l5d.const`](#const).

## Localhost

kind: `io.l5d.localhost`

The localhost transformer filters the list of addresses down to only addresses
that have the same IP address as localhost.  The IP of localhost is determined
by doing a one-time DNS lookup of the local hostname.  This transformer can be
used by an incoming router to route traffic only to local destinations.

## Specific Host

kind: `io.l5d.specificHost`

The specific host transformer filters the list of addresses down to only
addresses that have the same IP address as the specified host. This transformer
can be used by an incoming router to only route traffic to specific
destinations. This is useful when linkerd is running inside a Docker container
and the traffic is to be sent to another Docker container on the same host.

Key  | Default Value | Description
---- | ------------- | -----------
host | _required_    | The host to use.

## Port

kind: `io.l5d.port`

The port transformer replaces the port number in every address with a
configured value.  This can be used if there is an incoming linkerd router (or
other reverse-proxy) running on a fixed port on each host and you wish to send
traffic to that port instead of directly to the destination address.

Key | Default Value | Description
--- | ------------- | -----------
port | _required_ | The port number to use.

## DaemonSet (Kubernetes)

kind: `io.l5d.k8s.daemonset`

The DaemonSetTransformer maps each address in the destination NameTree to a
member of a given daemonset that is on the same /24 subnet.  Since each Kubernetes
node is its own /24 subnet, the result is that each destination address is
mapped to the member of the daemonset that is running on the same node.
This can be used to redirect traffic to a reverse-proxy that runs as a
daemonset.

This transformer assumes that there is a Kubernetes service for the daemonset
which can be used to find all pods in the daemonset.

Key | Default Value | Description
--- | ------------- | -----------
k8sHost | `localhost` | The Kubernetes master host.
k8sPort | `8001` | The Kubernetes master port.
namespace | _required_ | The Kubernetes namespace of the daemonset.
service | _required_ | The Kubernetes service name for the daemonset.
port | _required_ | The name of the daemonset port to use.
hostNetwork | `false` | If true, use nodeName instead of /24 subnet to determine which daemonset pod is on the destination node.  Set this to true if the daemonset is running with `hostNetwork: true`.

<aside class="notice">
The Kubernetes namer does not support TLS.  Instead, you should run `kubectl proxy` on each host,
which will create a local proxy for securely talking to the Kubernetes cluster API. See (the k8s guide)[https://linkerd.io/doc/latest/k8s/] for more information.
</aside>

## Localnode (Kubernetes)

kind: `io.l5d.k8s.localnode`

The localnode transformer filters the list of addresses down to only addresses
that are on the same /24 subnet as localhost.  Since each Kubernetes node is its own
/24 subnet, the result is that only addresses on the local node are used.

This transformer does not have any configuration properties but it does require
the `POD_IP` environment variable be set with the localhost IP address.  This is
most easily done with the
[Kubernetes downward API](http://kubernetes.io/docs/user-guide/downward-api/).

> In your container spec:

```
env:
- name: POD_IP
    valueFrom:
      fieldRef:
        fieldPath: status.podIP
```

Key | Default Value | Description
--- | ------------- | -----------
hostNetwork | `false` | If true, use nodeName to determine which pods are on the local node.  This adds the requirement that the `NODE_NAME` environment variable be set with the node name.  Set this to true if the pod is running with `hostNetwork: true`.

## Replace

kind: `io.l5d.replace`

The replace transformer replaces all bound names with a configurable path.
This differs from the const transformer in that if the original NameTree is
`Neg` then the result will be `Neg` as well.  This is useful if you want to
use a namer to check the validity of a name but then actually route to a
different name.

Key | Default Value | Description
--- | ------------- | -----------
path | _required_ | Bound names will be replaced with this path.

## Const

kind: `io.l5d.const`

The const transformer ignores the input and always returns a constant
configurable path.  This differs from the replace transformer in that it always
returns the configured path, even when the original NameTree is `Neg`.

Key | Default Value | Description
--- | ------------- | -----------
path | _required_ | Ignore the input and return this path.
