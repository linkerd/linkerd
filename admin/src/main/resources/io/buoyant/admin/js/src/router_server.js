"use strict";

define([
  'handlebars.runtime',
  'src/colors',
  'src/latency_color_util',
  'src/success_rate_graph',
  'src/utils',
  'template/compiled_templates'
], function(
  Handlebars,
  Colors,
  LatencyUtil,
  SuccessRateGraph,
  Utils,
  templates
) {
  var RouterServer = (function() {
    var template = templates.router_server;

    var desiredLatencyColors = Colors[3].colorFamily; // match the success rate graph
    var latencyLegend = LatencyUtil.createLatencyLegend(desiredLatencyColors);

    function getMetricDefinitions(routerName, serverName) {
      var defs = [
        {
          description: "Requests",
          metricSuffix: "requests"
        },
        {
          description: "Connections",
          metricSuffix: "connections",
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
      var latencyPartial = templates["latencies.partial"];
      Handlebars.registerPartial('latencyPartial', latencyPartial);

      var $metricsEl = $serverEl.find(".server-metrics");
      var $chartEl = $serverEl.find(".server-success-chart");
      var chart = SuccessRateGraph($chartEl, "#4AD8AC");

      var metricsHandler = function(data) {
        var transformedData = processData(data, routerName, server);
        var latencyData = _.get(data, ["rt", routerName, "server", server, "request_latency_ms"]);
        var coloredLatencyData = LatencyUtil.getLatencyData(latencyData, latencyLegend);

        renderServer($metricsEl, server, transformedData, coloredLatencyData);
        chart.updateMetrics(getSuccessRate(transformedData));
      }

      metricsCollector.registerListener("RouterServer_" + routerName + server, metricsHandler);

      return {};
    };
  })();

  return RouterServer;
});
