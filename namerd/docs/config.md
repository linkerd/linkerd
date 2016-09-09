# Introduction

> A namerd config example

```yaml
storage:
  kind: io.l5d.inMemory
  namespaces:
    galaxyquest: |
      /host       => /#/io.l5d.fs;
      /http/1.1/* => /host;
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

namerd's configuration is controlled via config file, which must be provided
as a command-line argument. It may be a local file path or `-` to
indicate that the configuration should be read from the standard input.

#### File Format

The configuration may be specified as a JSON or YAML object.

Key | Required | Description
--- | -------- | -----------
[admin](#administrative-interface) | no | Configures namerd's administrative interface. namerd admin has the same options as linkerd admin.
[interfaces](#interfaces) | no | Configures namerd's published network interfaces.
[storage](#storage) | yes | Configures namerd's storage backend.
[namers](https://linkerd.io/config/head/linkerd#namers) | no | Configures namerd's integration with various service discovery backends. namerd uses the same namers as linkerd.

### Administrative interface

```yaml
admin:
  port: 9991
```

namerd supports an administrative interface. The exposed admin port is configurable via a top-level `admin` section.

Key | Default Value | Description
--- | ------------- | -----------
port | `9991` | Port for the admin interface.
