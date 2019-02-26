# Mux Protocol (experimental)

>A mux router configuration that routes requests to port 9001

```yaml

routers:
- protocol: mux
  label: power-level-router
  dstPrefix: /overNineThousand
  dtab: |
    /overNineThousand => /$/inet/127.0.1/9001;
```

protocol: `mux`

Linkerd experimentally supports the [mux
protocol](https://twitter.github.io/finagle/guide/Protocols.html#mux).

## Mux Router Parameters

Key | Default Value | Description
--- | ------------- | -----------
dstPrefix | `/svc` | A path prefix used in `dtab`.

## Mux Server Parameters

Key | Default Value | Description
--- | ------------- | -----------
port | `4141` | The TCP port number.



