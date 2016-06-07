# Thrift Protocol

*(for the [routers](config.md#routers) key)*

Since the Thrift protocol does not encode a destination name in the message
itself, routing must be done per port. This implies one port per Thrift
service. For out-of-the-box configuration, this means that the contents of
`disco/thrift` will be treated as a newline-delimited list of `host:port`
combinations for a specific thrift service.

The default _dstPrefix_ is `/thrift`.

Router configuration options include:
* *thriftMethodInDst* -- if `true`, thrift method names are appended to
  destinations for outgoing requests. (default: false)

Thrift servers define additional parameters:

* *thriftFramed* -- if `true`, a framed thrift transport is used for incoming
  requests; otherwise, a buffered transport is used. Typically this setting
  matches the router's `thriftFramed` param. (default: true)
* *thriftProtocol* -- allows the thrift protocol to be chosen;
   currently supports 'binary' for `TBinaryProtocol` (default) and
   'compact' for `TCompactProtocol`. Typically this setting matches
   the router's client `thriftProtocol` param.

The default server _port_ is 4114.

Thrift also supports additional *client* parameters:

* *thriftFramed* -- if `true`, a framed thrift transport is used for outgoing
  requests; otherwise, a buffered transport is used. Typically this setting
  matches the router's servers' `thriftFramed` param. (default: true)
* *thriftProtocol* -- allows the thrift protocol to be chosen;
   currently supports `binary` for `TBinaryProtocol` (default) and
   `compact` for `TCompactProtocol`. Typically this setting matches
   the router's servers' `thriftProtocol` param.
* *attemptTTwitterUpgrade* -- controls whether thrift protocol upgrade should be
   attempted.  (default: true)

As an example: Here's a thrift router configuration that routes thrift--via
buffered transport using the TCompactProtocol --from port 4004 to port 5005

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
