# Announcers

## Serversets

`io.l5d.serversets`

Announce to ZooKeeper using the serverset format.

* *zkAddrs* -- list of ZooKeeper addresses:
  * *host* --  the ZooKeeper host.
  * *port* --  the ZooKeeper port.
* *pathPrefix* -- (optional) the ZooKeeper path under which services should be registered. (default:
  /discovery)
