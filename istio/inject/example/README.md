# Helloworld example

Example deployment using Daemonset linkerd and transparent proxying using our
[hello world example](https://github.com/linkerd/linkerd-examples/tree/master/docker/helloworld).

You'll notice that unlike our previous
[hello world](https://github.com/linkerd/linkerd-examples/blob/master/k8s-daemonset/k8s/hello-world.yml) or
[hello world legacy](https://github.com/linkerd/linkerd-examples/blob/master/k8s-daemonset/k8s/hello-world-legacy.yml),
we don't have `http_proxy` set. Also note that the service name has been changed
from `world-v1` to `world`.

```
# Deploy linkerd as a Daemonset

$ kubectl apply -f https://raw.githubusercontent.com/linkerd/linkerd-examples/master/k8s-daemonset/k8s/linkerd.yml

# Modify and deploy the helloworld config

$ LINKERD_PORT=4140
$ kubectl apply -f <(inject -f helloworld.yaml -linkerdPort $LINKERD_PORT)

# Test it out!

$ INGRESS_LB=$(kubectl get svc l5d -o jsonpath="{.status.loadBalancer.ingress[0].*}")
$ curl $INGRESS_LB:$LINKERD_PORT -H "Host: hello"
Hello (10.196.2.94) world (10.196.0.26)!!
```
