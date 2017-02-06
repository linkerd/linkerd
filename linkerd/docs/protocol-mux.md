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

linkerd experimentally supports the [mux
protocol](http://twitter.github.io/finagle/guide/Protocols.html#mux).

## Mux Router Parameters

Key | Default Value | Description
--- | ------------- | -----------
<<<<<<< HEAD
dstPrefix | `/s` | A path prefix used in `baseDtab`.
=======
dstPrefix | `mux` | A path prefix used in `dtab`.
>>>>>>> master

## Mux Server Parameters

Key | Default Value | Description
--- | ------------- | -----------
port | `4141` | The TCP port number.



