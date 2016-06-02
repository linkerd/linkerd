# Interfaces

*(for the [interfaces](config.md#interfaces) key)*

An interface is a published network interface to namerd.  An interface config
block supports the following params:

* *kind* -- Required. One of the supported interface plugins.
* *ip* -- Optional.  The local IP address on which to serve the namer interface
(defaults may be provided by plugins)
* *port* -- Optional.  The port number on which to server the namer interface.
(defaults may be provided by plugins)

## Thrift Name Interpreter

`io.l5d.thriftNameInterpreter`

A read-only interface providing `NameInterpreter` functionality over the ThriftMux protocol.

* default *ip*: 0.0.0.0 (wildcard)
* default *port*: 4100
* *retryBaseSecs* -- Optional. Base number of seconds to tell clients to wait
before retrying after an error.  (default: 600)
* *retryJitterSecs* -- Optional.  Maximum number of seconds to jitter retry
time by.  (default: 60)

## Http Controller

`io.l5d.httpController`

A read-write HTTP interface to the `storage`.

* default *ip*: loopback
* default *port*: 4180
