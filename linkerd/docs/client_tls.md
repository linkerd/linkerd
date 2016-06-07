# Client TLS

*(for the [tls](config.md#client_tls) key)*

A client TLS object describes how linkerd should use TLS when sending requests
to destination services.  A client TLS config block must contain a `kind`
parameter which indicates which client TLS plugin to use as well as any
parameters specific to the plugin.

### Example

```yaml
routers:
- ...
  client:
    tls:
      kind: io.l5d.static
      commonName: foo
      caCertPath: /foo/caCert.pem
```

## No Validation

`io.l5d.noValidation`

Skip hostname validation.  This is unsafe.

## Static

`io.l5d.static`

Use a single common name for all TLS requests.  This assumes that all servers
that the router connects to all use the same TLS cert (or all use certs
generated with the same common name).  This plugin supports the following
options:

* *commonName* -- Required.  The common name to use for all TLS requests.
* *caCertPath* -- Optional.  Use the given CA cert for common name validation.

## Bound Path

`io.l5d.boundPath`

Determine the common name based on the destination bound path.  This plugin
supports the following options:

* *caCertPath* -- Optional.  Use the given CA cert for common name validation.
* *names* -- Required.  A list of name matchers which each must be an object
  containing:
  * *prefix* -- A path prefix.  All destinations which match this prefix
    will use this entry to determine the common name.  Wildcards and variable
    capture are allowed (see: `io.buoyant.linkerd.util.PathMatcher`)
  * *commonNamePattern* -- The common name to use for destinations matching
    the above prefix.  Variables captured in the prefix may be used in this
    string.
* *strict* -- Optional. When true, paths that fail to match any prefixes throw
    an exception. Defaults to true.

For example,

```yaml
kind: io.l5d.boundPath
caCertPath: /foo/cacert.pem
names:
- prefix: "/#/io.l5d.fs/{host}"
  commonNamePattern: "{host}.buoyant.io"
 strict: false
```
