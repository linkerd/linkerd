# Transparent proxy inection

We can do transparent proxying of requests to linkerd via `iptables` rules.

This script injects an `initContainer` into the user's k8s config. This
`initContainer` sets up `iptables` rules for tranparent proxying of requests to a
[Daemonset linkerd](https://github.com/linkerd/linkerd-examples/blob/master/k8s-daemonset/k8s/linkerd.yml).

It injects the following initContainer (which you could add to your config
manually if you would rather not use the script). The script uses the
`pod.beta.kubernetes.io/init-containers` annotation, which you would need to use
if you are running a version of the Kubernetes Apiserver before 1.6.

```
initContainers:
  - name: init
    image: buoyantio/istio-init:v1
    args:
      - -p
      - "4140" # port of the Daemonset linkerd's incoming router
    imagePullPolicy: Always
    securityContext:
      capabilities:
        add:
        - NET_ADMIN
```

It is based on Istio's method of
[injecting sidecars](https://github.com/istio/pilot/blob/master/doc/proxy-injection.md),
Ideally this code would go somewhere with the istioctl code, and reuse that code
more directly, but this seems to be in transit right now. This `prepare_proxy.sh`
sets up iptables rules for transparently proxying requests to a Daemonset linkerd
(rather than a sidecar proxy, which Istio
[currently uses](https://github.com/istio/pilot/blob/master/docker/prepare_proxy.sh)).

## Usage

```
go install

# Inject init container into your yaml and apply
kubectl apply -f <(inject -f example/helloworld.yaml -linkerdPort 4140)

# See output of script before applying
inject -f example/helloworld.yaml -o result.yml -linkerdPort 4140
```
