# Introduction

> A Namerd config example

```yaml
storage:
  kind: io.l5d.inMemory
  namespaces:
    galaxyquest: |
      /host       => /#/io.l5d.fs;
      /svc => /host;
namers:
- kind: io.l5d.fs
  rootDir: examples/disco
interfaces:
- kind: io.l5d.thriftNameInterpreter
  port: 4100
  ip: 0.0.0.0
  retryBaseSecs:  600
  retryJitterSecs: 60
- kind: io.l5d.httpController
  port: 4321
```

Welcome to the Configuration Reference for Namerd!

Namerd's configuration is controlled via a config file, which must be provided
as a command-line argument. It may be a local file path or `-` to
indicate that the configuration should be read from the standard input.

#### File Format

The configuration may be specified as a JSON or YAML object.

Key | Required | Description
--- | -------- | -----------
[admin](#administrative-interface) | no | Configures Namerd's administrative interface. Namerd admin has the same options as Linkerd admin.
[interfaces](#interfaces) | yes | Configures Namerd's published network interfaces.
[storage](#storage) | yes | Configures Namerd's storage backend.
[namers](https://linkerd.io/config/head/linkerd#namers) | no | Configures Namerd's integration with various service discovery backends. Namerd uses the same namers as Linkerd.
[telemetry](https://linkerd.io/config/head/linkerd#telemetry) | no | Configures Namerd's metrics instrumentation. Namerd does not support tracing, so tracers provided by telemeters are ignored.

### Administrative interface

```yaml
admin:
  ip: 127.0.0.1
  port: 9991
```

Namerd supports an administrative interface. The exposed admin port and
IP are configurable via a top-level `admin` section.

Key | Default Value | Description
--- | ------------- | -----------
ip | loopback address | IP for the admin interface. A value like 0.0.0.0 configures admin to listen on all local IPv4 interfaces.
port | `9990` | Port for the admin interface.
socketOptions | none | Socket options to set for the admin interface. See [Socket Options](https://linkerd.io/config/head/linkerd/index.html#socket-options)
shutdownGraceMs | 10000 | maximum grace period before the Namerd process exits
tls | no tls | The admin interface will serve over TLS if this parameter is provided. see [TLS](#server-tls).
workerThreads | 2 | The number of worker threads used to serve the admin interface.

#### Administrative endpoints

Namerd's admin interface mirrors Linkerd's, with one exception: the
`/delegator.json` endpoint in Linkerd is served as `/dtab/delegator.json` in
Namerd.

To learn about default admin endpoints, have a look at
[Linkerd's administrative interface](https://linkerd.io/config/head/linkerd/index.html#administrative-interface).

For metrics information, Namerd also exposes
[Linkerd's CommonMetrics endpoints](https://linkerd.io/config/head/linkerd/index.html#commonmetrics)
by default.
