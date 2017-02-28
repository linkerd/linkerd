"use strict";

define([
  'jQuery',
  'template/compiled_templates'
  ], function($, templates) {

  var RequestTotals = (function() {
    var template = templates.request_totals;

    var metricDefinitions = [
      {
        description: "Current requests",
        metric: "requests",
        getMetrics: function(data) {
          return sumMetric(data, "requests", false, this.prefix);
        },
        prefix: "srv"
      },
      {
        description: "Pending",
        metric: "load",
        getMetrics: function(data) {
          return sumMetric(data, "load", true, this.prefix);
        },
        prefix: "srv",
        isGauge: true
      },
      {
        description: "Incoming Connections",
        getMetrics: function(data) {
          return sumMetric(data, "connections", true, this.prefix);
        },
        prefix: "srv",
        metric: "connections",
        isGauge: true
      },
      {
        description: "Outgoing Connections",
        getMetrics: function(data) {
          return sumMetric(data, "connections", true, this.prefix); // is this connections or connects? is this right?
        },
        prefix: "dst.id",
        metric: "connections",
        isGauge: true
      }
    ];

    function sumMetric(data, metric, isGauge, prefix) {
      return _.reduce(data, function(mem, routerData, router) {
        _.mapValues(_(routerData).get(prefix), function(entityData) {
          mem += _.get(entityData, [metric, isGauge ? "value" : "delta"]) || 0;
        });
        return mem;
      }, 0);
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

      function desiredMetrics(treeMetrics) {
        if (!treeMetrics) return [];
        else {
          var metrics = _.map(treeMetrics.rt, function(routerData, router) {
            var serverData = _.map(routerData.srv, function(serverData, server) {
              return _.map(metricDefinitions, function(defn) {
                return ["rt", router, "srv", server, defn.metric, defn.isGauge ? "gauge" : "counter"];
              });
            });

            var clientData = _.map(_.get(routerData, "dst.id"), function(clientData, client) {
              return ["rt", router, "dst", "id", client, "connections", "gauge"];
            });
            return _.concat(serverData, clientData);
          });
        }
        return _.flatMap(metrics);
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
