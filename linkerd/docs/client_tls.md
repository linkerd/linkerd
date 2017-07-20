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

In order to accept incoming TLS traffic, the tls parameter must be defined on
the server.

Key | Default Value | Description
--- | ------------- | -----------
certPath | _required_ | File path to the TLS certificate file.
keyPath | _required_ | File path to the TLS key file.
requireClientAuth | false | If true, only accept requests with valid client certificates.
caCertPath | none | File path to the CA cert to validate the client certificates.

See [Transparent TLS with linkerd](https://blog.buoyant.io/2016/03/24/transparent-tls-with-linkerd/) for more on how to generate certificate
and key files.

## Client TLS

>Client TLS is defined in the client section of routers:

```yaml
routers:
- protocol: http
  client:
    tls:
      commonName: linkerd.io
      trustCerts:
      - /certificates/cacert.pem
      clientAuth:
        certPath: /certificates/cert.pem
        keyPath: /certificates/key.pem
```

In order to send outgoing tls traffic, the tls parameter must be defined as a
[client parameter](#client-parameters).

A client TLS object describes how linkerd should use TLS when sending requests
to destination services.

Key               | Default Value                              | Description
----------------- | ------------------------------------------ | -----------
disableValidation | false                                      | Enable this to skip hostname validation (unsafe).
commonName        | _required_ unless disableValidation is set | The common name to use for all TLS requests.
trustCerts        | empty list                                 | A list of file paths of CA certs to use for common name validation.
clientAuth        | none                                       | A client auth object used to sign requests.

If present, a client auth object must contain two properties:

Key      | Default Value | Description
---------|---------------|-------------
certPath | _required_    | File path to the TLS certificate file.
keyPath  | _required_    | File path to the TLS key file.  Must be in PKCS#8 format.

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
```
