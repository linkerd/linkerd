# Introduction

> A Linkerd config example

```yaml
admin:
  port: 9990

routers:
- protocol: http
  label: int-http
  dtab: |
    /svc       => /#/io.l5d.fs;
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
    /svc => /#/io.l5d.fs/thrift;

namers:
- kind: io.l5d.fs
  rootDir: disco

telemetry:
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

Welcome to the Configuration Reference for Linkerd!

Linkerd's configuration is controlled via a configuration file, which must be provided
as a command-line argument. It may be a local file path or `-` to
indicate that the configuration should be read from the standard input.
For convenience, the release package includes a default `linkerd.yaml` file in
the `config/` directory.

#### File Format

The configuration may be specified as a JSON or YAML object. There are no requirements on field ordering, though it's generally good style to start a router with the _protocol_. Five top level keys are supported:

Key | Required | Description
--- | -------- | -----------
[admin](#administrative-interface) | no | Configures Linkerd's administrative interface.
[routers](#routers-intro) | yes | Configures Linkerd's RPC support for various protocols.
[namers](#namers-and-service-discovery) | no | Configures Linkerd's integration with various service discovery backends.
[telemetry](#telemetry-intro) | no | Configures Linkerd's metrics instrumentation.
[usage](#usage) | no | Configures Linkerd usage reporting.


### Administrative interface

```yaml
admin:
  ip: 127.0.0.1
  port: 9990
  tls:
    certPath: /foo/cert.pem
    keyPath: /foo/key.pem
```

Linkerd supports an administrative interface, both as a web ui and a collection
of json endpoints. The exposed admin port and ip to listen on are configurable
via a top-level `admin` section.

Key | Default Value | Description
--- | ------------- | -----------
ip | loopback address | IP for the admin interface. A value like 0.0.0.0 configures admin to listen on all local IPv4 interfaces.
port | `9990` | Port for the admin interface.
socketOptions | none | Socket options to set for the admin interface.
httpIdentifierPort | none | Port for the http identifier debug endpoint.
shutdownGraceMs | 10000 | maximum grace period before the Linkerd process exits
tls | no tls | The admin interface will serve over TLS if this parameter is provided. See [TLS](#server-tls).
security | no restrictions | The admin interface can block sensitive endpoints if configured. See [Admin Security](#admin-security)
workerThreads | 2 | The number of worker threads used to serve the admin interface.

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

Default admin endpoints available in both Linkerd and Namerd:

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
`/admin/registry.json` | displays how Linkerd is currently configured across a variety of dimensions including the client stack, server stack, flags, service loader values, system properties, environment variables, build properties and more
`/admin/server_info` | build information about this Linkerd
`/admin/shutdown` | initiate a graceful shutdown
`/admin/threads` | capture the current stacktraces, set `Accept: application/json` to force json
`/admin/threads.json` | identical to `admin/threads`
`/admin/tracing` | enable (`/admin/tracing?enable=true`) or disable tracing (`/admin/tracing?disable=true`)
`/config.json` | current Linkerd configuration, this should match your config file

Endpoints only available in Linkerd:

Endpoint | Description
-------- | -----------
`/bound-names.json` | list of all known bound names from configured namers. namers must support the `EnumeratingNamer` trait to make this available, currently only supported by the [io.l5d.k8s](#kubernetes-service-discovery) and [io.l5d.fs](#file-based-service-discovery) namers.
`/delegator.json` | given `path`, `dtab`, and an optional `namespace` param, return the delegation tree
`/logging.json` | currently configured loggers and log levels

Note that in addition to a default set of admin endpoints, Linkerd plugins may
dynamically add their own endpoints.

HTTP Identifier Endpoints (running on `httpIdentifierPort`):

Endpoint | Description
-------- | -----------
`/` | identifies the request and returns a json map of identified results

This administrative interface was originally based on
[TwitterServer](https://twitter.github.io/twitter-server), more information may
be found at
[TwitterServer HTTP Admin interface](https://twitter.github.io/twitter-server/Admin.html).

#### Admin security

> Example to disable the endpoint `/admin/shutdown` exclusively

```yaml
admin:
  ip: 127.0.0.1
  port: 9990
  security:
    pathBlacklist:
    - ^/admin/shutdown$
```

By default all administrative endpoints are accessible.
This might not always be desired as this allows for example everybody to shutdown the process if the administrative port is not closed because metrics should be polled from Linkerd.
The admin security configuration allows to whitelist or blacklist dedicated administrative endpoints.

```yaml
admin:
  ip: 127.0.0.1
  port: 9990
  security:
    uiEnabled: true
    controlEnabled: false
    pathWhitelist:
    - ^/logging[.]json$
```

The security configuration supports these parameters allowing fine grained control over the administrative endpoints that are available.

Key | Default Value | Description
--- | ------------- | -----------
uiEnabled | true | Configures if all endpoints that belong to the category required to run the administrative ui are available
controlEnabled | true | Configures if the endpoints that belong to the category to control Linkerd are available, i.e. `/admin/shutdown` and `/logging.json`
diagnosticsEnabled | true | Configures if all other endpoints are available.
pathWhitelist | empty list | Configures paths via regular expressions that should be available for disabled endpoint categories
pathBlacklist | empty list | Configures additional paths via regular expressions that should not be available for enabled categories

An administrative endpoint is available if its category is enabled and it is not on the blacklist or if its category is disabled and it is on the whitelist.

### Routers Intro

> A minimal Linkerd configuration example, which forwards all requests on `localhost:8080` to `localhost:8888`

```yaml
routers:
- protocol: http
  dtab: /svc/* => /$/inet/127.1/8888
  servers:
  - port: 8080
```

All configurations must define a **routers** key, the value of which
must be an array of router configurations. Each router implements RPC for a supported protocol. Linkerd doesn't need to understand the payload in an RPC call, but it does need to know enough about the protocol to determine the logical name of the destination.

See [routers](#routers).

### Namers and Service Discovery

```yaml
namers:
- kind: io.l5d.fs
  rootDir: disco
```

Linkerd supports a variety of common service discovery backends, including
ZooKeeper and Consul. Linkerd provides abstractions on top of service discovery
lookups that allow the use of arbitrary numbers of service discovery backends,
and for precedence and failover rules to be expressed between them. This logic
is governed by the [routing](#router-parameters) configuration.

Naming and service discovery are configured via the `namers` section of the
configuration file.  A namer acts on paths that start with `/#` followed by the
namer's prefix. See [namers](#namers).

### Telemetry Intro

```yaml
telemetry:
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
or export. Telemetry data can be collected and exported from a Linkerd process by
configuring telemeters via a top-level `telemetry` section. See
[telemetry](#telemetry).

### Usage

> Example usage config

```yaml
usage:
 orgId: my-org
```

In order to make improvements and prioritize features, we'd like to gain a
broader picture of how users run Linkerd. Linkerd gathers anonymized usage data,
and sends it to Buoyant once an hour.  This behavior can be configured or
disabled in the usage config.

The kind of data captured is as follows:

1. How Linkerd is configured (The kinds of namers, initializers, identifiers, transformers, protocols, & interpreters used)
2. What environments Linkerd is running in (Operating System, Container orchestration solution),
3. How Linkerd performs in those contexts (JVM performance, Number of requests served)

We do not collect the labels of namers/routers, designated service addresses or
directories, dtabs, request/response data, or any other possibly identifying information.

To see the exact payload that is sent query `localhost:9990/admin/metrics/usage`

Key | Default Value | Description
--- | ------------- | -----------
orgId | empty by default | Optional string of your choosing that identifies your organization
enabled | true | If set to true, data is sent to Buoyant once per hour

### Socket Options

Linkerd supports configuring socket level options for any given interface.
i.e. the admin and router interfaces. These configurations are only available 
on Linux 3.9 distributions and newer.

Key | Default Value | Description
--- | ------------- | -----------
noDelay | true | If set to true, enables the use of `TCP_NODELAY` on a socket interface
reuseAddr | true | If set to true, enables the `SO_REUSEADDR` option
reusePort | false | If set to true, enables the `SO_REUSEPORT` option, which can be used to bind another Linkerd process to the same interface port.
readTimeoutSeconds | Unbounded | Configures this client or server with given transport-level socket read timeout.
writeTimeoutSeconds | Unbounded | Configures this client or server with given transport-level socket write timeout.
keepAlive | false | If set to true, enables the `SO_KEEPALIVE` option, which will enable keep alive on the socket.
