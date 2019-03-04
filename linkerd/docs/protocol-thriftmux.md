# ThriftMux Protocol (experimental)

> This config routes thriftmux from port 4400 to port 5005.

```yaml
routers:
- protocol: thriftmux
  experimental: true
  label: port-shifter
  dtab: |
    /svc => /$/inet/127.1/5005;
  servers:
  - port: 4400
    ip: 0.0.0.0
```

protocol: `thriftmux`

Linkerd _experimentally_ supports the thriftmux protocol.

Thriftmux protocol is capable of routing traffic to pure thrift service and
will use [Thrift](https://twitter.github.io/finagle/guide/Protocols.html#thrift) protocol on the client.

Protocol configuration uses the same parameters as
[Thrift protocol](https://linkerd.io/config/head/linkerd#thrift-protocol).

## ThriftMux Router Parameters

See [Thrift Router Parameters](https://linkerd.io/config/head/linkerd#thrift-router-parameters)

## ThriftMux Server Parameters

See [Thrift Server Parameters](https://linkerd.io/config/head/linkerd#thrift-server-parameters)

## ThriftMux Client Parameters

See [Thrift Client Parameters](https://linkerd.io/config/head/linkerd#thrift-client-parameters)
