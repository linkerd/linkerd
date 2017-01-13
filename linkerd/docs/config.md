# Introduction

> A linkerd config example

```yaml
admin:
  port: 9990

routers:
- protocol: http
  label: int-http
  baseDtab: |
    /host       => /#/io.l5d.fs;
    /http/1.1/* => /host;
  identifier:
    kind: io.l5d.methodAndHost
    httpUriInDst: true
  servers:
  - port: 4140
    ip: 0.0.0.0

- protocol: thrift
  servers:
  - port: 8081
    ip: 0.0.0.0
    thriftFramed: true
  client:
    thriftFramed: true
  thriftMethodInDst: false
  baseDtab: |
    /thrift => /#/io.l5d.fs/thrift;

namers:
- kind: io.l5d.fs
  rootDir: disco

tracers:
- kind: io.l5d.zipkin
  sampleRate: 0.02

telemetry:
- kind: io.l5d.commonMetrics
- kind: io.l5d.statsd
  experimental: true
  prefix: linkerd
  hostname: 127.0.0.1
  port: 8125
  gaugeIntervalMs: 10000
  sampleRate: 0.01
- kind: io.l5d.tracelog
  sampleRate: 0.2
  level: TRACE
```

Welcome to the Configuration Reference for linkerd!

linkerd's configuration is controlled via config file, which must be provided
as a command-line argument. It may be a local file path or `-` to
indicate that the configuration should be read from the standard input.
For convenience, the release package includes a default `linkerd.yaml` file in
the `config/` directory.

#### File Format

The configuration may be specified as a JSON or YAML object. There are no requirements on field ordering, though it's generally good style to start a router with the _protocol_. Four top level keys are supported:

Key | Required | Description
--- | -------- | -----------
[admin](#administrative-interface) | no | Configures linkerd's administrative interface.
[routers](#routers) | yes | Configures linkerd's RPC support for various protocols.
[namers](#namers-and-service-discovery) | no | Configures linkerd's integration with various service discovery backends.
[telemetry](#telemetry) | no | Configures linkerd's metrics instrumentation.
[tracers](#tracers) | no | Configures linkerd's request instrumentation.


### Administrative interface

```yaml
admin:
  ip: 127.0.0.1
  port: 9990
```

linkerd supports an administrative interface, both as a web ui and a collection
of json endpoints. The exposed admin port and ip to listen on are configurable
via a top-level `admin` section.

Key | Default Value | Description
--- | ------------- | -----------
ip | `0.0.0.0` | IP for the admin interface.
port | `9990` | Port for the admin interface.

### Routers

> A minimal linkerd configuration example, which forwards all requests on `localhost:8080` to `localhost:8888`

```yaml
routers:
- protocol: http
  baseDtab: /http => /$/inet/127.1/8888
  servers:
  - port: 8080
```

All configurations must define a **routers** key, the value of which
must be an array of router configurations. Each router implements RPC for a supported protocol. linkerd doesn't need to understand the payload in an RPC call, but it does need to know enough about the protocol to determine the logical name of the destination.

See [routers](#routers1).

### Namers and Service Discovery

```yaml
namers:
- kind: io.l5d.fs
  rootDir: disco
```

linkerd supports a variety of common service discovery backends, including
ZooKeeper and Consul. linkerd provides abstractions on top of service discovery
lookups that allow the use of arbitrary numbers of service discovery backends,
and for precedence and failover rules to be expressed between them. This logic
is governed by the [routing](#router-parameters) configuration.

Naming and service discovery are configured via the `namers` section of the
configuration file.  A namer acts on paths that start with `/#` followed by the
namer's prefix. See [namers](#namers).

### Telemetry

```yaml
telemetry:
- kind: io.l5d.commonMetrics
- kind: io.l5d.statsd
  experimental: true
  prefix: linkerd
  hostname: 127.0.0.1
  port: 8125
  gaugeIntervalMs: 10000
  sampleRate: 0.01
- kind: io.l5d.tracelog
  sampleRate: 0.2
  level: TRACE
```

A telemeter may receive stats and trace annotations, i.e. to send to a collector
or export. Telemetry data can be collected and exported from a linkerd process by
configuring telemeters via a top-level `telemetry` section. See
[telemetry](#telemetry14).

### Tracers

```yaml
tracers:
- kind: io.l5d.zipkin
  sampleRate: 0.02
```

Requests that are routed by linkerd are also traceable using Finagle's built-in
tracing instrumentation. Trace data can be exported from a linkerd process by
configuring tracers via a top-level `tracers` section. See [tracers](#tracers13).
