"use strict";

define([
  'src/colors',
  'src/success_rate_graph',
  'src/utils',
  'template/compiled_templates'
], function(colors, SuccessRateGraph, Utils, templates) {
  var RouterServer = (function() {
    var template = templates.router_server;

    var metricToColorShade = {
      "max": "light",
      "p9990": "tint",
      "p99": "neutral",
      "p95": "shade",
      "p50": "dark"
    }
    var latencyKeys = _.keys(metricToColorShade);

    function createLatencyLegend(colorLookup) {
      return _.mapValues(metricToColorShade, function(shade) {
        return colorLookup[shade];
      });
    }
    var latencyLegend = createLatencyLegend(colors[3].colorFamily);

    function getMetricDefinitions(routerName, serverName) {
      var defs = [
        {
          description: "Requests",
          metricSuffix: "requests"
        },
        {
          description: "Pending",
          metricSuffix: "load",
          isGauge: true
        },
        {
          description: "Successes",
          metricSuffix: "success",
          getRate: function(data) {
            var successRate = new Utils.SuccessRate(data.success || 0, data.failures || 0);
            return {
              prettyRate: successRate.prettyRate(),
              rawRate: successRate.successRate
            }
          }
        },
        {
          description: "Failures",
          metricSuffix: "failures"
        }
      ];
      return _.map(defs, function(def) {
        var treeMetricKeys = genServerStat(routerName, serverName, def.metricSuffix);
        def.treeMetricRoot = treeMetricKeys.metricRoot;
        return def;
      })
    }

    function genServerStat(routerName, serverName, stat, isGauge) {
      var metricKeys = ["rt", routerName, "server", serverName, stat, isGauge ? "gauge" : "counter"];
      return {
        metric: metricKeys,
        metricRoot: _.take(metricKeys, 5)
      };
    }

    function getLatencyData(data, routerName, serverName) {
      var latencyData = _.get(data, ["rt", routerName, "server", serverName, "request_latency_ms"]);

      return _.map(latencyKeys, function(key) {
        return {
          latencyLabel: key,
          latencyValue: _.get(latencyData, "stat." + key),
          latencyColor: latencyLegend[key]
        };
      });
    }

    function renderServer($container, server, data, latencyData) {
      var metrics = _.reduce(data, function(metrics, d) {
        metrics[d.metricSuffix] = {
          description: d.description,
          value: d.value,
          rate: !d.rate ? null : d.rate.prettyRate
        };
        return metrics;
      }, {});

      $container.html(template({
        server: server,
        metrics: metrics,
        latencies: latencyData
      }));
    }

    function processData(data, router, server) {
      var lookup = {}; // track success/failure # for SuccessRate() calculation
      var metricDefinitions = getMetricDefinitions(router, server);
      var populatedMetrics = _.map(metricDefinitions, function(defn) {
        var serverData = _.get(data, defn.treeMetricRoot);

        if (!_.isEmpty(serverData)) {
          defn.value = serverData[defn.isGauge ? "gauge" : "delta"];
          lookup[defn.metricSuffix] = defn.value;
        }

        return defn;
      });

      // we need to have completed the first pass through the definitions to get
      // success and failure counts
      _.each(populatedMetrics, function(defn) {
        if(_.isFunction(defn.getRate)) {
          defn.rate = defn.getRate(lookup);
        }
      });

      return populatedMetrics;
    }

    function getSuccessRate(data) {
      var suc = _.find(data, ["metricSuffix", "success"]);
      var successRate = suc.rate.rawRate  === -1 ? 1 : suc.rate.rawRate;

      return [{ name: "successRate", delta: successRate * 100 }];
    }

    return function (metricsCollector, server, $serverEl, routerName) {
      var $metricsEl = $serverEl.find(".server-metrics");
      var $chartEl = $serverEl.find(".server-success-chart");
      var chart = SuccessRateGraph($chartEl, "#4AD8AC");

      var metricsHandler = function(data) {
        var transformedData = processData(data, routerName, server);
        var latencyData = getLatencyData(data, routerName, server);
        renderServer($metricsEl, server, transformedData, latencyData);
        chart.updateMetrics(getSuccessRate(transformedData));
      }

      metricsCollector.registerListener(metricsHandler);

      return {};
    };
  })();

  return RouterServer;
});
