"use strict";

define([
  'jQuery'
], function($) {
  /**
   * Utilities for building and updating router information from raw
   * key-val metrics.
   *
   * Produces a data structure in the form:
   *
   *   {
   *     routerName: {
   *       label: "routerName"
   *       prefix: "rt/routerName/",
   *       dstIds: {
   *         dstName: {
   *           label: "dstName",
   *           router: "routerName",
   *           prefix: "rt/routerName/dst/id/dstName/",
   *           metrics: {...}
   *         },
   *       },
   *       servers: [
   *         { label: "0.0.0.0/8080",
   *           ip: "0.0.0.0",
   *           port: 8080,
   *           router: "routerName",
   *           prefix: "rt/routerName/srv/0.0.0.0/8080/",
   *           metrics: {...}
   *         }
   *       ],
   *       metrics: {...}
   *     }
   *   }
   */
  var Routers = (function() {
    function mk(router, label, prefix) {
      return {
        router: router,
        label: label,
        prefix: prefix,
        metrics: {requests: -1}
      };
    }

    function mkRouter(label) {
      var prefix = "rt/" + label + "/",
          router = mk(label, label, prefix);
      router.dstIds = {};
      router.servers = [];
      return router;
    }

    function mkDst(router, id) {
      var prefix = "rt/" + router + "/dst/id/" + id + "/";
      return mk(router, id, prefix);
    }

    function mkServer(router, ip, port) {
      var label = ip + "/" + port,
          prefix = "rt/" + router + "/srv/" + label + "/",
          server = mk(router, label, prefix);
      server.ip = ip;
      server.port = port;
      return server;
    }

    function onAddedClients(handler) {
      var wrapper = function(events, clients) {
        handler(clients);
      }
      $("body").on("addedClients", wrapper);
      return wrapper;
    }

    // Updates router clients and metrics from raw-key val metrics.
    function update(routers, metrics) {
      // first, check for new clients and add them
      var addedClients = [];
      _.each(metrics.rt, function(routerData, routerName) {
        var router = routers[routerName];
        if(!router) return;
        _.each(_.get(routerData, "dst.id"), function(clientData, client) {
          if(!router.dstIds[client]) {
            var addedClient = mkDst(routerName, client);
            addedClients.push(addedClient);
            router.dstIds[client] = addedClient;
          }
        });
      });

      if (addedClients.length)
        $("body").trigger("addedClients", [addedClients]);


      // TODO: Remove any unused clients. This will require more intelligent
      // color assignment to ensure client => color mapping is deterministic.
    }

    function updateServers(routers, metrics) {
      _.each(metrics.rt, function(routerData, routerName) {
        var router = routers[routerName] = routers[routerName] || mkRouter(routerName);
        _.each(routerData.srv, function(serverData, serverName) {
          var ipAndPort = serverName.split("/");
          var ip = ipAndPort[0], port = ipAndPort[1];
          router.servers.push(mkServer(routerName, ip, port));
        });
      });
    }

    /**
     * A constructor that initializes an object describing all of linker's routers,
     * with stats, from a raw key-val metrics blob.
     *
     * Returns an object that may be updated with additional data.
     */
    return function(metrics, metricsCollector) {
      var routers = {};

      // servers are only added the first time.
      updateServers(routers, metrics);

      // clients and metrics are initialized now, and then may be updated later.
      update(routers, metrics);

      if (!_.isEmpty(metricsCollector)) {
        var metricsHandler = function(data) { update(routers, data); }
        metricsCollector.registerListener(metricsHandler, function(_metrics) {});
      }

      return {
        data: routers,

        /** Updates metrics (and clients) on the underlying router data structure. */
        update: function(metrics) { update(this.data, metrics); },

        /** Add event handler for new clients */
        onAddedClients: onAddedClients,

        //convenience methods
        servers: function(routerName) {
          var servers = []
          if (routerName && this.data[routerName]) {
            servers = this.data[routerName].servers;
          } else {
            servers = _(this.data).map('servers').flatten().value();
          }
          return _.sortBy(servers, 'label');
        },

        clients: function(routerName) {
          var clients = []
          if (routerName && this.data[routerName]) {
            clients = _.values(this.data[routerName].dstIds);
          } else {
            clients = _(this.data).map(function(router) { return _.values(router.dstIds); }).flatten().value();
          }
          return _.sortBy(clients, 'label');
        }
      };
    };
  })();

  return Routers;
});
