# Introduction

> A linkerd config example

```yaml
admin:
  port: 9990

routers:
- protocol: http
  label: int-http
  dtab: |
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
  dtab: |
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
[routers](#routers-intro) | yes | Configures linkerd's RPC support for various protocols.
[namers](#namers-and-service-discovery) | no | Configures linkerd's integration with various service discovery backends.
[telemetry](#telemetry-intro) | no | Configures linkerd's metrics instrumentation.
[tracers](#tracers-intro) | no | Configures linkerd's request instrumentation.


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

#### Administrative endpoints

> Example admin requests

```bash
curl :9990/admin
curl :9990/admin/tracing?enable=true
curl -H "Accept: application/json" :9990/admin/lint
curl -H "Accept: application/json" :9990/admin/threads
curl ":9990/admin/pprof/profile?seconds=10&hz=100"
curl ":9990/delegator.json?path=/http/1.1/GET/foo&dtab=/http/*=>/$/inet/127.1/9990"
```

Default admin endpoints available in both linkerd and namerd:

Endpoint | Description
-------- | -----------
`/admin` | retrieve a list of all available admin endpoints
`/admin/announcer` | set of announcement chains that have run through the [announcers](#announcers)
`/admin/contention` | call stacks of blocked and waiting threads
`/admin/lint` | results for all registered linters, set `Accept: application/json` to force json
`/admin/lint.json` | identical to `admin/lint`
`/admin/ping` | simple health check endpoint, returns `pong`
`/admin/pprof/contention` | CPU contention profile which identifies blocked threads (Thread.State.BLOCKED), in pprof format. The process will be profiled for 10 seconds at a frequency of 100 hz. These values can be controlled via HTTP request parameters `seconds` and `hz` respectively.
`/admin/pprof/heap` | heap profile computed by the heapster agent, output is in pprof format
`/admin/pprof/profile` | CPU usage profile in pprof format. The process will be profiled for 10 seconds at a frequency of 100 hz. These values can be controlled via HTTP request parameters `seconds` and `hz` respectively.
`/admin/registry.json` | displays how linkerd is currently configured across a variety of dimensions including the client stack, server stack, flags, service loader values, system properties, environment variables, build properties and more
`/admin/server_info` | build information about this linkerd
`/admin/shutdown` | initiate a graceful shutdown
`/admin/threads` | capture the current stacktraces, set `Accept: application/json` to force json
`/admin/threads.json` | identical to `admin/threads`
`/admin/tracing` | enable (`/admin/tracing?enable=true`) or disable tracing (`/admin/tracing?disable=true`)
`/config.json` | current linkerd configuration, this should match your config file

Endpoints only available in linkerd:

Endpoint | Description
-------- | -----------
`/bound-names.json` | list of all known bound names from configured namers. namers must support the `EnumeratingNamer` trait to make this available, currently only supported by the [io.l5d.k8s](#kubernetes-service-discovery) and [io.l5d.fs](#file-based-service-discovery) namers.
`/delegator.json` | given `path`, `dtab`, and an optional `namespace` param, return the delegation tree
`/logging.json` | currently configured loggers and log levels

Note that in addition to a default set of admin endpoints, linkerd plugins may
dynamically add their own endpoints.

This administrative interface was originally based on
[TwitterServer](https://twitter.github.io/twitter-server), more information may
be found at
[TwitterServer HTTP Admin interface](https://twitter.github.io/twitter-server/Admin.html).

### Routers Intro

> A minimal linkerd configuration example, which forwards all requests on `localhost:8080` to `localhost:8888`

```yaml
routers:
- protocol: http
  dtab: /http => /$/inet/127.1/8888
  servers:
  - port: 8080
```

All configurations must define a **routers** key, the value of which
must be an array of router configurations. Each router implements RPC for a supported protocol. linkerd doesn't need to understand the payload in an RPC call, but it does need to know enough about the protocol to determine the logical name of the destination.

See [routers](#routers).

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

### Telemetry Intro

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
[telemetry](#telemetry).

### Tracers Intro

```yaml
tracers:
- kind: io.l5d.zipkin
  sampleRate: 0.02
```

Requests that are routed by linkerd are also traceable using Finagle's built-in
tracing instrumentation. Trace data can be exported from a linkerd process by
configuring tracers via a top-level `tracers` section. See [tracers](#tracers).
