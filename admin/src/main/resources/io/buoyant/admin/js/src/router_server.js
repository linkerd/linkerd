"use strict";

define([
  'src/success_rate_graph',
  'src/query',
  'src/utils',
  'template/compiled_templates'
], function(SuccessRateGraph, Query, Utils, templates) {
  var RouterServer = (function() {
    var template = templates.router_server;

    function getMetricDefinitions(routerName, serverName) {
      var defs = [
        {
          description: "Requests",
          metricSuffix: "requests",
          treeMetric: genServerStat(routerName, serverName, "requests"),
          query: Query.serverQuery().withRouter(routerName).withServer(serverName).withMetric("requests").build()
        },
        {
          description: "Pending",
          metricSuffix: "load",
          isGauge: true,
          treeMetric: genServerStat(routerName, serverName, "load", true),
          query: Query.serverQuery().withRouter(routerName).withServer(serverName).withMetric("load").build()
        },
        {
          description: "Successes",
          metricSuffix: "success",
          treeMetric: genServerStat(routerName, serverName, "success"),
          query: Query.serverQuery().withRouter(routerName).withServer(serverName).withMetric("success").build(),
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
          metricSuffix: "failures",
          treeMetric: genServerStat(routerName, serverName, "failures"),
          query: Query.serverQuery().withRouter(routerName).withServer(serverName).withMetric("failures").build()
        }
      ];
      return _.map(defs, function(def) {
        var treeMetricKeys = genServerStat(routerName, serverName, def.metricSuffix);
        def.treeMetric = treeMetricKeys.metric;
        def.treeMetricRoot = treeMetricKeys.metricRoot;
        return def;
      })
    }

    function genServerStat(routerName, serverName, stat, isGauge) {
      var metricKeysRoot = ["rt", routerName, "srv", serverName, stat];
      return {
        metric: metricKeysRoot.concat(!isGauge ? "counter" : "gauge"),
        metricRoot: metricKeysRoot
      };
    }

    function renderServer($container, server, data) {
      var metrics = _.reduce(data, function(metrics, d) {
        metrics[d.metricSuffix] = {
          description: d.description,
          value: d.value,
          rate: !d.rate ? null : d.rate.prettyRate
        };
        return metrics;
      }, {});

      $container.html(template({
        server: server.label,
        metrics: metrics
      }));
    }

    function processData(data, router, server) {
      var lookup = {}; // track success/failure # for SuccessRate() calculation
      var metricDefinitions = getMetricDefinitions(router, server);
      var populatedMetrics = _.map(metricDefinitions, function(defn) {
        var serverData = _.get(data, defn.treeMetricRoot);

        if (!_.isEmpty(serverData)) {
          defn.value = defn.isGauge ? serverData.value : serverData.delta;
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
        var transformedData = processData(data.treeSpecific, routerName, server.label);
        renderServer($metricsEl, server, transformedData);
        chart.updateMetrics(getSuccessRate(transformedData));
      }

      var getDesiredMetrics = function(metrics) {
        var metricDefinitions = getMetricDefinitions(routerName, server.label);
        return _.map(metricDefinitions, "treeMetric");
      }

      metricsCollector.registerListener(metricsHandler, getDesiredMetrics);

      return {};
    };
  })();

  return RouterServer;
});
