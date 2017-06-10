# Protobuf Sources

**gogoproto**: Replicated from [the gogo/protobuf repo](https://github.com/gogo/protobuf/tree/master/gogoproto).

**google/protobuf**: Replicated from
[the google protobuf repo](https://github.com/google/protobuf/tree/master/src/google/protobuf).
Ideally we wouldn't need these and could use the protobuf-java library directly. The java packages have been changed to
 `com.google.local` to avoid protobuf-java conflicts.

**google/rpc**: Replcated from [the googleapis repo](https://github.com/googleapis/googleapis/tree/master/google/rpc).

**mixer/v1**: Replicated from [the istio mixer api repo](https://github.com/istio/api/tree/master/mixer).

**proxy/v1**: Replicated from [the istio proxy api repo](https://github.com/istio/api/tree/master/proxy).

# Istio installation

The protobuf files above require Istio and istioctl built from master.

Follow instructions at https://istio.io/docs/tasks/installing-istio.html, but
use https://github.com/istio/istio/ rather than a versioned release.

To install istioctl from master:

```bash
$ git clone https://github.com/istio/istio.git ; cd istio
$ source istio.VERSION
$ export MANAGER_HUB=$PILOT_HUB  # Important/needed at the moment
$ export MANAGER_TAG=$PILOT_TAG  # ditto
$ curl -L $ISTIOCTL_URL/istioctl-osx > ./istioctl  # change the -osx to your env if needed
```
