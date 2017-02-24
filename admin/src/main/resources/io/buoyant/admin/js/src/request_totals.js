"use strict";

define([
  'jQuery',
  'src/query', // TODO: remove
  'template/compiled_templates'
  ], function($, Query, templates) {

  var RequestTotals = (function() {
    var template = templates.request_totals;

    var metricDefinitions = [
      {
        description: "Current requests",
        metric: "requests",
        getMetrics: function(data) {
          return getTreeServerMetric(data, "requests");
        }
      },
      {
        description: "Pending",
        metric: "load",
        getMetrics: function(data) {
          return getTreeServerMetric(data, "load", true);
        },
        isGauge: true
      },
      {
        description: "Incoming Connections",
        getMetrics: function(data) {
          return getTreeServerMetric(data, "connections", true);
        },
        metric: "connections",
        isGauge: true
      },
      {
        description: "Outgoing Connections",
        getMetrics: function(data) {
          return getTreeClientMetric(data, "connections", true); // is this connections or connects? is this right?
        },
        metric: "connections",
        isGauge: true
      }
    ];

    function getTreeServerMetric(data, metric, isGauge) {
      return _.reduce(data, function(mem, routerData, router) {
        _.map(routerData.srv, function(serverData, server) {
          mem += _.get(serverData, [metric, isGauge ? "value" : "delta"]) || 0; // can replace value with gauge?
        });
        return mem;
      }, 0);
    }

    function getTreeClientMetric(data, metric, isGauge) {
      return _.reduce(data, function(mem, routerData, router) {
        _.map(_.get(routerData, "dst.id"), function(clientData, client) {
          mem += _.get(clientData, [metric, isGauge ? "value" : "delta"]) || 0; // can replace value with gauge?
        });
        return mem;
      }, 0);
    }

    function desiredMetrics(possibleMetrics, treeMetrics) {
      if (!treeMetrics) return [];
      else {
        var metrics = _.map(treeMetrics.rt, function(routerData, router) {
          var serverData = _.map(routerData.srv, function(serverData, server) {
            return _.map(metricDefinitions, function(defn) {
              return ["rt", router, "srv", server, defn.metric, defn.isGauge ? "gauge" : "counter"];
            });
          });

          var clientData = _.map(_.get(routerData, "dst.id"), function(clientData, client) {
            return ["rt", router, "dst", "id", client, "connects", "gauge"];
          });
          return _.flatMap(serverData).concat(clientData);
        });
      }
      return _.flatMap(metrics);
    }

    function render($root, metricData) {
      $root.html(template({
        metrics : metricData
      }));
    }

    return function(metricsCollector, selectedRouter, $root) {
      function onMetricsUpdate(data) {
        var transformedData = _.map(metricDefinitions, function(defn) {
          var metrics = defn.getMetrics(data.treeSpecific.rt);

          return {
            description: defn.description,
            value: metrics
          };
        });

        render($root, transformedData);
      }

      if (!selectedRouter || selectedRouter === "all") { //welcome to my world of hacks
        render($root, metricDefinitions);
        metricsCollector.registerListener(onMetricsUpdate, desiredMetrics);
      } else {
        $root.hide();
      }
      return {};
    }
  })();

  return RequestTotals;
});
