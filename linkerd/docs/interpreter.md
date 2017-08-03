# Interpreter

> Example Interpreter Configuration

```yaml
routers:
- ...
  interpreter:
    kind: io.l5d.namerd
    dst: /$/inet/1.2.3.4/4180
```

An interpreter determines how names are resolved.

<aside class="notice">
These parameters are available to the identifier regardless of kind. Identifiers may also have kind-specific parameters.
</aside>

Key | Default Value | Description
--- | ------------- | -----------
kind | `default` | Either [`default`](#default), [`io.l5d.namerd`](#namerd-thrift), [`io.l5d.namerd.http`](#namerd-http), [`io.l5d.mesh`](#namerd-mesh), or [`io.l5d.fs`](#file-system).
transformers | No transformers | A list of [transformers](#transformer) to apply to the resolved addresses.

## Default

kind: `default`

The default interpreter resolves names via the configured
[`namers`](#namers), with a fallback to the default Finagle
`Namer.Global` that handles paths of the form `/$/`.

## namerd thrift

kind: `io.l5d.namerd`

The namerd thrift interpreter offloads the responsibilities of name resolution
to the namerd service.  Any namers configured in this linkerd are not used.  The
interpreter uses namerd's long-poll thrift interface
(`io.l5d.thriftNameInterpreter`). Note that the protocol that the interpreter
uses to talk to namerd is unrelated to the protocols of linkerd's routers.

Key | Default Value | Description
--- | ------------- | -----------
dst | _required_ | A Finagle path locating the namerd service.
namespace | `default` | The name of the namerd dtab to use.
retry | see [namerd retry](#namerd-retry) | An object configuring retry backoffs for requests to namerd.
tls | no tls | Requests to namerd will be made using TLS if this parameter is provided.  It must be a [namerd client TLS](#namerd-client-tls) object.

### namerd retry

Key | Default Value | Description
--- | ------------- | -----------
baseSeconds | 5 seconds | The base number of seconds to wait before retrying.
maxSeconds | 10 minutes | The maximum number of seconds to wait before retrying.

### namerd client tls

Key | Default Value | Description
--- | ------------- | -----------
commonName | _required_ | The common name to use for namerd requests.
caCert | N/A | The path to the CA cert used for common name validation.

## namerd http

kind: `io.l5d.namerd.http`

The namerd http interpreter offloads the responsibilities of name resolution to
the namerd service.  Any namers configured in this linkerd are not used.  The
interpreter uses namerd's HTTP streaming interface (`io.l5d.httpController`).
Note that the protocol that the interpreter uses to talk to namerd is unrelated
to the protocols of linkerd's routers.

Key | Default Value | Description
--- | ------------- | -----------
experimental | _required_ | Because the http interpreter is still considered experimental, you must set this to `true` to use it.
dst | _required_ | A Finagle path locating the namerd service.
namespace | `default` | The name of the namerd dtab to use.
retry | see [namerd retry](#namerd-retry) | An object configuring retry backoffs for requests to namerd.
tls | no tls | Requests to namerd will be made using TLS if this parameter is provided.  It must be a [client TLS](#client-tls) object.

## namerd mesh

kind: `io.l5d.mesh`

The namerd mesh interpreter offloads the responsibilities of name resolution to
the namerd service.  Any namers configured in this linkerd are not used.  The
interpreter uses namerd's gRPC mesh interface (`io.l5d.mesh`). Note that the
protocol that the interpreter uses to talk to namerd is unrelated to the
protocols of linkerd's routers.

Key | Default Value | Description
--- | ------------- | -----------
experimental | _required_ | Because the mesh interpreter is still considered experimental, you must set this to `true` to use it.
dst | _required_ | A Finagle path locating the namerd service.
root | `/default` | A single-element Finagle path representing the namerd namespace.
retry | see [namerd retry](#namerd-retry) | An object configuring retry backoffs for requests to namerd.
tls | no tls | Requests to namerd will be made using TLS if this parameter is provided.  It must be a [client TLS](#client-tls) object.

## File-System

kind: `io.l5d.fs`

The file-system interpreter resolves names via the configured
[`namers`](#namers), just like the default interpreter, but also uses
a dtab read from a file on the local file-system.  The specified file is watched
for changes so that the dtab may be edited live.

Key | Default Value | Description
--- | ------------- | -----------
dtabFile | _required_ | The file-system path to a file containing a dtab.
