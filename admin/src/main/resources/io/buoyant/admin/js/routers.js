"use strict";

/* globals Query */
/* exported Routers */

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

  var clientRE = Query.clientQuery().withMetric("requests").build(),
      serverRE = Query.serverQuery().withMetric("requests").build();

  var mkColor = function() {
    var colorIdx = 0;
    var colors = [
      "8,64,129",
      "123,204,196",
      "8,104,172",
      "168,221,181",
      "43,140,190",
      "204,235,197",
      "78,179,211",
      "224,243,219"
    ];

    //returns the next available color
    return function() {
      return colors[colorIdx++ % colors.length];
    }
  }();

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
    var dst = mk(router, id, prefix);
    dst.color = mkColor();
    return dst;
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

    _.each(metrics, function(metric, key) {
      var match = key.match(clientRE);
      if (match) {
        var name = match[1], id = match[2],
            router = routers[name];
        if (router && !router.dstIds[id]) {
          var addedClient = mkDst(name, id);
          addedClients.push(addedClient)
          router.dstIds[id] = addedClient;
        }
      }
    });

    if (addedClients.length)
      $("body").trigger("addedClients", [addedClients]);


    // TODO: Remove any unused clients. This will require more intelligent
    // color assignment to ensure client => color mapping is deterministic.

    // then, attach metrics to each appropriate scope

    _.each(metrics, function(metric, key){
      var scope = findByMetricKey(routers, key);
      if (scope) {
        var descoped = key.slice(scope.prefix.length);
        scope.metrics[descoped] = metric;
      }
    });
  }

  function updateServers(routers, metrics) {
    _.each(metrics, function(metric, key) {
      var match = key.match(serverRE);
      if (match) {
        var name = match[1], ipAndPort = match[2].split("/");
        var ip = ipAndPort[0], port = ipAndPort[1];
        var router = routers[name] = routers[name] || mkRouter(name);
        router.servers.push(mkServer(name, ip, port));
      }
    });
  }

  function findMatchingRouter(routers, key) {
    return _.find(routers, function(router) { return key.indexOf(router.prefix) === 0; });
  }
  function findMatchingServer(servers, key) {
    return _.find(servers, function(server) { return key.indexOf(server.prefix) === 0; });
  }
  function findMatchingDst(dsts, key) {
    return _.find(dsts, function(dst) { return key.indexOf(dst.prefix) === 0; });
  }
  function findByMetricKey(routers, key) {
    var router = findMatchingRouter(routers, key);
    if (router) {
      var server = findMatchingServer(router.servers, key);
      if (server) return server;

      var dst = findMatchingDst(router.dstIds, key);
      if (dst) return dst;
    }
    return router; // may be undefined
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
      var metricsHandler = function(data) { update(routers, data.general); }
      metricsCollector.registerListener(metricsHandler, function(_metrics) {});
    }

    return {
      data: routers,

      /** Updates metrics (and clients) on the underlying router data structure. */
      update: function(metrics) { update(this.data, metrics); },

      /** Finds a scope (router, dst, or server) associated with a scoped metric name. */
      findByMetricKey: function(key) { return findByMetricKey(this.data, key); },

      /** Finds a router associated with a scoped metric name. */
      findMatchingRouter: function(key) { return findMatchingRouter(this.data, key); },

      /** Add event handler for new clients */
      onAddedClients: onAddedClients,

      //convenience methods
      servers: function(routerName) {
        if (routerName && this.data[routerName]) {
          return this.data[routerName].servers;
        } else {
          return _(this.data).map('servers').flatten().value();
        }
      },

      clients: function(routerName) {
        if (routerName && this.data[routerName]) {
          return _.values(this.data[routerName].dstIds);
        } else {
          return _(this.data).map(function(router) { return _.values(router.dstIds); }).flatten().value();
        }
      }
    };
  };
})();
