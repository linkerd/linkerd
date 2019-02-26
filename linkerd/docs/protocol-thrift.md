# Thrift Protocol


> This config routes thrift (via buffered transport using the TCompactProtocol) from port 4004 to port 5005

```yaml
routers:
- protocol: thrift
  label: port-shifter
  dtab: |
    /svc => /$/inet/127.1/5005;
  thriftProtocol: compact
  servers:
  - port: 4004
    ip: 0.0.0.0
    thriftFramed: false
  client:
    thriftFramed: false
```

protocol: `thrift`

If the [TTwitter thrift](https://twitter.github.io/finagle/guide/Protocols.html#thrift) protocol is
used, the value from the `dest` request header will be used for routing:

> Dtab Path Format For Thrift
```
  / dstPrefix [/ dest] [/ thriftMethod ]
```

Otherwise, the Thrift protocol does not encode a destination name in the message
itself and the dest part of the path will be absent.

## Thrift Router Parameters

Key | Default Value | Description
--- | ------------- | -----------
dstPrefix | `/svc` | A path prefix used in `dtab`.
thriftMethodInDst | `false` | If `true`, thrift method names are appended to destinations for outgoing requests.
thriftProtocol | `binary` | Either `binary` (TBinaryProtocol) or `compact` (TCompactProtocol). Applies to both clients and servers.


## Thrift Server Parameters

Key | Default Value | Description
--- | ------------- | -----------
port | `4114` | The TCP port number.
thriftFramed | `true` | If `true`, a framed thrift transport is used for incoming requests; otherwise, a buffered transport is used. Typically this setting matches the router's `thriftFramed` param.

## Thrift Client Parameters

Key | Default Value | Description
--- | ------------- | -----------
thriftFramed | `true` | If `true`, a framed thrift transport is used for outgoing requests; otherwise, a buffered transport is used. Typically this setting matches the router's servers' `thriftFramed` param.
attemptTTwitterUpgrade | `false` | Controls whether thrift protocol upgrade should be attempted.
