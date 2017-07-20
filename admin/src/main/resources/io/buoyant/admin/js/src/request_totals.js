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
        prefix: "server"
      },
      {
        description: "Pending",
        metric: "load",
        getMetrics: function(data) {
          return sumMetric(data, "load", true, this.prefix);
        },
        prefix: "server",
        isGauge: true
      },
      {
        description: "Incoming Connections",
        getMetrics: function(data) {
          return sumMetric(data, "connections", true, this.prefix);
        },
        prefix: "server",
        metric: "connections",
        isGauge: true
      },
      {
        description: "Outgoing Connections",
        getMetrics: function(data) {
          return sumMetric(data, "connections", true, this.prefix);
        },
        prefix: "client",
        metric: "connections",
        isGauge: true
      }
    ];

    function sumMetric(data, metric, isGauge, prefix) {
      return _.reduce(data, function(mem, routerData) {
        _.mapValues(_(routerData).get(prefix), function(entityData) {
          mem += _.get(entityData, [metric, isGauge ? "gauge" : "delta"]) || 0;
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
          var metrics = defn.getMetrics(data.rt);

          return {
            description: defn.description,
            value: metrics
          };
        });

        render($root, transformedData);
      }

      if (!selectedRouter || selectedRouter === "all") { //welcome to my world of hacks
        render($root, metricDefinitions);
        metricsCollector.registerListener("RequestTotals", onMetricsUpdate);
      } else {
        $root.hide();
      }
      return {};
    }
  })();

  return RequestTotals;
});
