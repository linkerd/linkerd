# Configuration

namerd's configuration is controlled via config file, which must be provided
as a command-line argument. It may be a local file path or `-` to
indicate that the configuration should be read from the standard input.

## File Format

The configuration may be specified as a JSON or YAML object, as described
below.

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

<a name="storage"></a>
## Storage

All configurations must define a **storage** key which must be a
[storage](storage.md) object.

<a name="namers"></a>
## Namers

Naming and service discovery are configured via the `namers` section of the
configuration file. In this file, `namers` is an array of objects each of which
must be a [namer](../../linkerd/docs/namer.md).

<a name="interfaces"></a>
## Interfaces

A top-level `interfaces` section controls the published network interfaces to
namerd. It is an array of [interface](interface.md) objects.
