# Introduction

> A namerd config example

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

Welcome to the Configuration Reference for namerd!

namerd's configuration is controlled via a config file, which must be provided
as a command-line argument. It may be a local file path or `-` to
indicate that the configuration should be read from the standard input.

#### File Format

The configuration may be specified as a JSON or YAML object.

Key | Required | Description
--- | -------- | -----------
[admin](#administrative-interface) | no | Configures namerd's administrative interface. namerd admin has the same options as linkerd admin.
[interfaces](#interfaces) | yes | Configures namerd's published network interfaces.
[storage](#storage) | yes | Configures namerd's storage backend.
[namers](https://linkerd.io/config/head/linkerd#namers) | no | Configures namerd's integration with various service discovery backends. namerd uses the same namers as linkerd.
[telemetry](https://linkerd.io/config/head/linkerd#telemetry) | no | Configures namerd's metrics instrumentation. Namerd does not support tracing, so tracers provided by telemeters are ignored.

### Administrative interface

```yaml
admin:
  ip: 127.0.0.1
  port: 9991
```

namerd supports an administrative interface. The exposed admin port and
IP are configurable via a top-level `admin` section.

Key | Default Value | Description
--- | ------------- | -----------
ip | `0.0.0.0` | IP for the admin interface.
port | `9991` | Port for the admin interface.

#### Administrative endpoints

Namerd's admin interface mirrors linkerd's, with one exception: the
`/delegator.json` endpoint in linkerd is served as `/dtab/delegator.json` in
namerd.

To learn about default admin endpoints, have a look at
[linkerd's administrative interface](https://linkerd.io/config/head/linkerd/index.html#administrative-interface).

For metrics information, namerd also exposes
[linkerd's CommonMetrics endpoints](https://linkerd.io/config/head/linkerd/index.html#commonmetrics)
by default.
