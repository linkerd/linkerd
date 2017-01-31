# TLS

## Server TLS

```yaml
routers:
- protocol: http
  servers:
  - port: 4140
    # accept incoming TLS traffic from remote linkerd
    tls:
      certPath: /certificates/certificate.pem
      keyPath: /certificates/key.pem
  dtab: |
    /http => /$/inet/127.1/8080;
```

In order to accept incoming tls traffic, the tls parameter must be defined on
the server.

Key | Default Value | Description
--- | ------------- | -----------
certPath | _required_ | File path to the TLS certificate file.
keyPath | _required_ | File path to the TLS key file.

See [Transparent TLS with linkerd](https://blog.buoyant.io/2016/03/24/transparent-tls-with-linkerd/) for more on how to generate certificate
and key files.

## Client TLS

>Client TLS is defined in the client section of routers:

```yaml
routers:
- protocol: http
  client:
    tls:
      kind: io.l5d.noValidation
```

In order to send outgoing tls traffic, the tls parameter must be defined on
the client.

A client TLS object describes how linkerd should use TLS when sending requests
to destination services.

Key | Default Value | Description
--- | ------------- | -----------
kind | _required_ | Either [io.l5d.noValidation](#no-validation-tls), [io.l5d.static](#static-tls), or [io.l5d.boundPath](#tls-with-bound-path).

<aside class="notice">
TLS objects may also have protocol-specific parameters.
</aside>

### No Validation TLS

```yaml
tls:
  kind: io.l5d.noValidation
```

kind: `io.l5d.noValidation`

<aside class="warning">This skips hostname validation and is unsafe.</aside>

### Static TLS

```yaml
tls:
  kind: io.l5d.static
  commonName: foo
  caCertPath: /foo/caCert.pem
```

kind: `io.l5d.static`

Uses a single common name for all TLS requests.  This assumes all servers
that the router connects to use the same TLS cert (or all use certs
generated with the same common name).

Key | Default Value | Description
--- | ------------- | -----------
commonName | _required_ | The common name to use for all TLS requests.
caCertPath | N/A | The path to the CA cert used for common name validation.

### TLS with Bound Path

```yaml
tls:
  kind: io.l5d.boundPath
  caCertPath: /foo/cacert.pem
  names:
  - prefix: "/#/io.l5d.fs/{host}"
    commonNamePattern: "{host}.buoyant.io"
  strict: false
```

kind: `io.l5d.boundPath`

Determine the common name based on the destination bound path.  This plugin
supports the following options:

Key | Default Value | Description
--- | ------------- | -----------
caCertPath | ? | The path to the CA cert used for common name validation.
names | _required_ | A list of [name matchers](#bound-path-name-matchers).
strict | true | When true, paths that fail to match any prefixes throw an exception.

#### Bound Path Name Matchers

Key | Default Value | Description
--- | ------------- | -----------
prefix | _required_ | A path prefix.  All destinations which match this prefix will use this entry to determine the common name.  Wildcards and variable capture are allowed (see: `io.buoyant.namer.util.PathMatcher`).
commonNamePattern | _required_ | The common name to use for destinations matching the above prefix.  Variables captured in the prefix may be used in this string.

See [Transparent TLS with linkerd](https://blog.buoyant.io/2016/03/24/transparent-tls-with-linkerd/) for more on how boundPath matches prefixes when routing requests.



