# Announcers

An announcer registers servers in service discovery.  Each server may specify
a list of concrete names to announce as in the [announce](config.md#announce)
server key.  Each announcer has a prefix and will only announce names that
begin with that prefix.

An announcer config block has the following parameters:

* *kind* -- The name of the announcer plugin
* *prefix* -- This announcer will announce names beginning with `/#/<prefix>`.
  Some announcers may configure a default prefix; see the specific announcer
  section for details.
* *announcer-specific parameters*.

## Serversets

`io.l5d.serversets`

Announce to ZooKeeper using the serverset format.

* *zkAddrs* -- list of ZooKeeper addresses:
  * *host* --  the ZooKeeper host.
  * *port* --  the ZooKeeper port.
* *pathPrefix* -- (optional) the ZooKeeper path under which services should be registered. (default:
  /discovery)
