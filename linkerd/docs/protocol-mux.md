# Mux Protocol (experimental)

linkerd experimentally supports the [mux
protocol](http://twitter.github.io/finagle/guide/Protocols.html#mux).

The default _dstPrefix_ is `/mux`.
The default server _port_ is 4141.

```yaml

routers:
- protocol: mux
  label: power-level-router
  dstPrefix: /overNineThousand
  baseDtab: |
    /overNineThousand => /$/inet/127.0.1/9001;
```
As an example: Here's a mux router configuration that routes requests to port 9001
