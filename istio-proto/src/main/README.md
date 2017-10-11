# Source files from Istio repos

* [protobuf](./protobuf)
  * [gogoproto](./protobuf/gogoproto): from [the gogo/protobuf repo](https://github.com/gogo/protobuf/tree/master/gogoproto)
  * [google](./protobuf/google)
    * [protobuf](./protobuf/google/protobuf): from
        [the google protobuf repo](https://github.com/google/protobuf/tree/master/src/google/protobuf).
        Ideally we wouldn't need these and could use the protobuf-java library
        directly. The java packages have been changed to `com.google.local` to
        avoid protobuf-java conflicts.
    * [rpc](./protobuf/google/rpc): from [the googleapis repo](https://github.com/googleapis/googleapis/tree/master/google/rpc)
  * [mixer/v1](./protobuf/mixer/v1): from [a sha of the istio mixer api repo](https://github.com/istio/api/tree/7d82318c70c7ba8611eed585ac1a8da44a005adb/mixer/v1), specified by a [mixer 0.1.6 release dependency](https://github.com/istio/mixer/blob/0.1.6/WORKSPACE#L181)
  * [proxy/v1/config](./protobuf/proxy/v1/config): from [a sha of the istio proxy api repo](https://github.com/istio/api/tree/6e481630954efcad10c0ab43244e8991a5a36bfc/proxy/v1/config), specified by a [pilot 0.1.6 release dependency](https://github.com/istio/pilot/blob/0.1.6/WORKSPACE#L408)
* [resources/mixer/v1/global_dictionary.yaml](./resources/mixer/v1/global_dictionary.yaml): from [the istio mixer api repo](https://github.com/istio/api/blob/master/mixer/v1/global_dictionary.yaml), for use with future versions of Mixer

# Istio installation

Follow instructions at https://istio.io/docs/tasks/installing-istio.html to install Istio 0.1.6.
