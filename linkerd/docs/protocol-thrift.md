# Thrift Protocol


> This config routes thrift (via buffered transport using the TCompactProtocol) from port 4004 to port 5005

```yaml
routers:
- protocol: thrift
  label: port-shifter
  baseDtab: |
    /thrift => /$/inet/127.1/5005;
  servers:
  - port: 4004
    ip: 0.0.0.0
    thriftFramed: false
    thriftProtocol: compact
  client:
    thriftFramed: false
    thriftProtocol: compact
```

protocol: `thrift`

Since the Thrift protocol does not encode a destination name in the message
itself, routing must be done per port. This implies one port per Thrift
service. For out-of-the-box configuration, this means that the contents of
`disco/thrift` will be treated as a newline-delimited list of `host:port`
combinations for a specific thrift service.

## Thrift Router Parameters

Key | Default Value | Description
--- | ------------- | -----------
dstPrefix | `thrift` | A path prefix used in `baseDtab`.
thriftMethodInDst | `false` | If `true`, thrift method names are appended to destinations for outgoing requests.


## Thrift Server Parameters

Key | Default Value | Description
--- | ------------- | -----------
port | `4114` | The TCP port number.
thriftFramed | `true` | If `true`, a framed thrift transport is used for incoming requests; otherwise, a buffered transport is used. Typically this setting matches the router's `thriftFramed` param.
thriftProtocol | `binary` | Either `binary` (TBinaryProtocol) or `compact` (TCompantProtocol). Typically this setting matches the router's client `thriftProtocol` param.

## Thrift Client Parameters

Key | Default Value | Description
--- | ------------- | -----------
thriftFramed | `true` | If `true`, a framed thrift transport is used for outgoing requests; otherwise, a buffered transport is used. Typically this setting matches the router's servers' `thriftFramed` param.
thriftProtocol | `binary` | Either `binary` (TBinaryProtocol) or `compact` (TCompantProtocol). Typically this setting matches the router's servers' `thriftProtocol` param.
attemptTTwitterUpgrade | `true` | Controls whether thrift protocol upgrade should be attempted.




