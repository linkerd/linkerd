# TLS

## Server TLS

```yaml
routers:
- protocol: http
  servers:
  - port: 4140
    # accept incoming TLS traffic from remote Linkerd
    tls:
      certPath: /certificates/certificate.pem
      keyPath: /certificates/key.pem
  dtab: |
    /http => /$/inet/127.1/8080;
```

In order to accept incoming TLS traffic, the tls parameter must be defined on
the server.

Key | Default Value | Description
--- | ------------- | -----------
enabled | true | Enable TLS on outgoing connections.
certPath | _required_ | File path to the TLS certificate file.
intermediateCertsPath | none | Path to a file containing a CA certificate chain to support the server certificate.
keyPath | _required_ | File path to the TLS key file.
requireClientAuth | false | If true, only accept requests with valid client certificates.
caCertPath | none | File path to the CA cert to validate the client certificates.
protocols | unspecified | The list of TLS protocols to enable (TLSv1.2)

See [Transparent TLS with Linkerd](https://blog.linkerd.io/2016/03/24/transparent-tls-with-linkerd/) for more on how to generate certificate
and key files.

## Client TLS

>Client TLS is defined in the client section of routers:

```yaml
routers:
- protocol: http
  client:
    tls:
      commonName: linkerd.io
      trustCertsBundle: /certificates/cacert.pem
      clientAuth:
        certPath: /certificates/cert.pem
        keyPath: /certificates/key.pem
      protocols:
      - TLSv1.2
```

In order to send outgoing tls traffic, the tls parameter must be defined as a
[client parameter](#client-parameters).

A client TLS object describes how Linkerd should use TLS when sending requests
to destination services.

Key               | Default Value                              | Description
----------------- | ------------------------------------------ | -----------
disableValidation | false                                      | Enable this to skip hostname validation (unsafe). Setting `disableValidation: true` is incompatible with `clientAuth`.
commonName        | _required_ unless disableValidation is set | The common name to use for all TLS requests.
trustCerts        | empty list                                 | A list of file paths of CA certs to use for common name validation (deprecated, please use trustCertsBundle).
trustCertsBundle  | empty                                      | A file path of CA certs bundle to use for common name validation
clientAuth        | none                                       | A client auth object used to sign requests.
protocols         | unspecified                                | The list of TLS protocols to enable

If present, a client auth object must contain two properties:

Key                   | Default Value | Description
----------------------|---------------|-------------
certPath              | _required_    | File path to the TLS certificate file.
intermediateCertsPath | none          | Path to a file containing a CA certificate chain to support the client certificate.
keyPath               | _required_    | File path to the TLS key file.  Must be in PKCS#8 format.

<aside class="warning">
Setting `disableValidation: true` will force the use of the JDK SSL provider which does not support client auth. Therefore, `disableValidation: true` and `clientAuth` are incompatible.
</aside>

Any variables captured from the client prefix may be used in the common name.

```yaml
routers:
- protocol: http
  client:
    kind: io.l5d.static
    configs:
    - prefix: /#/io.l5d.fs/{service}
      tls:
        commonName: "{service}.linkerd.io"
        trustCerts:
        - /certificates/cacert.pem
        clientAuth:
          certPath: /certificates/cert.pem
          keyPath: /certificates/key.pem
        protocols:
        - TLSv1.2          
```

### Client TLS and transformers
If you use transformers, each transformer will prepend a transformer prefix to the client name to 
indicate that it has been transformed.  Transformer prefixes always start with `/%`. 
When using any kind of transformer with client TLS using `io.l5d.static`, ensure that the client
TLS's `prefix` field starts with at least the first transformer that is applied to a client name in 
the dtab. This will ensure that TLS is initiated between the Linkerd client and any downstream 
service receiving the intended TLS traffic. For example given a configuration:

```yaml
routers:
- protocol: http
  interpreter:
      kind: default
      transformers:
      - kind: io.l5d.port
        port: 4141
  dtab: | 
    /svc => /#/io.l5d.fs;
  client:
    kind: io.l5d.static
    configs:
    - prefix: /%/io.l5d.port/4141/#/io.l5d.fs/
      tls:
        commonName: "linkerd.io"
        trustCerts:
        - /certificates/cacert.pem
        clientAuth:
          certPath: /certificates/cert.pem
          keyPath: /certificates/key.pem
```
Since the base client name is `/#/io.l5d.fs/hello` and the port transformer applies 
`/%/io.l5d.port/4141` as a transformer prefix, the transformed client name becomes 
`/%/io.l5d.port/4141/#/io.l5d.fs/<client name>`. The full client name generated by Linkerd for service 
`hello` would look like `/%/io.l5d.port/#/io.l5d.fs/4141/hello`.

A quick way to find out this information is to look through Linkerd's admin dashboard UI. On the 
initial dashboard page, the `client` section displays active clients used to connect to downstream 
services. Clicking on any of the client names in that section will reveal the full client name. 
The text that appears can be used to assign a `prefix` to the client TLS section. 
