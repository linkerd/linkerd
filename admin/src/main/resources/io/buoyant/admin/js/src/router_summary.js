"use strict";

define([
  'jQuery',
  'src/query',
  'src/utils',
  'src/bar_chart',
  'template/compiled_templates'
], function($,
  Query,
  Utils,
  BarChart,
  templates
) {
  var RetriesBarChart = function($container) {
    function displayPercent(percent) {
      return _.isNull(percent) ? " - " : Math.round(percent * 100) + "%";
    }

    function getColor(percent) {
      if (percent < 0.5) return "red";
      else if (percent < 0.75) return "orange";
      else return "green";
    }

    function getPercent(data, configuredBudget) {
      var retryPercent = !data["requests"] ? null : (data["retries"] || 0) / data["requests"];
      var budgetRemaining = Math.max(configuredBudget - (retryPercent || 0), 0);
      var healthBarPercent = Math.min(budgetRemaining / configuredBudget, 1);

      return {
        percent: healthBarPercent,
        label: {
          description: "Retry budget available",
          value: displayPercent(budgetRemaining) + " / " + displayPercent(configuredBudget)
        },
        warningLabel: retryPercent < configuredBudget ? null : "budget exhausted"
      }
    }

    var retriesBarChart = new BarChart($container, getColor);

    return {
      update: function(data, retryBudget) {
        retriesBarChart.update(getPercent(data, retryBudget));
      }
    }
  }

  var RouterSummary = (function() {
    var DEFAULT_BUDGET = 0.2 // default 20%
    var template = templates.router_summary;

    function processResponses(data, routerName) {
      var process = function(metricName) { return processServerResponse(data, routerName, metricName); };
      var pathRetries = processPathResponse(data, routerName, "retries/total");
      var requeues = processClientResponse(data, routerName, "retries/requeues");

      var result = {
        router: routerName,
        load: process("load"),
        requests: process("requests"),
        success: process("success"),
        failures: process("failures"),
        retries: (pathRetries || 0) + (requeues || 0)
      }
      var rates = getSuccessAndFailureRate(result);
      return  $.extend(result, rates);
    }

    function processServerResponse(data, routerName, metricName) {
      var datum = Query.filter(Query.serverQuery().allServers().withRouter(routerName).withMetric(metricName).build(), data);
      return _.sumBy(datum, "delta");
    }

    function processClientResponse(data, routerName, metricName) {
      var datum = Query.filter(Query.clientQuery().allClients().withRouter(routerName).withMetric(metricName).build(), data);
      return _.sumBy(datum, "delta");
    }

    function processPathResponse(data, routerName, metricName) {
      var datum = Query.filter(Query.pathQuery().allPaths().withRouter(routerName).withMetric(metricName).build(), data);
      return _.sumBy(datum, "delta");
    }

    function getSuccessAndFailureRate(result) {
      if (_.isUndefined(result.failures)) result.failures = null;
      var successRate = new Utils.SuccessRate(result.success || 0, result.failures || 0);
      return {
        successRate: successRate.prettyRate(),
        failureRate: getFailureRate(result)
      };
    }

    function getFailureRate(datum) {
      // TODO: #198 remove or refactor with SuccessRate in utils
      // there's some discussion as to whether we should include both success
      // and failure rate.  this is a very sketchy implementation of this until
      // we decide for sure
      if (datum.requests === 0) {
        return "N/A";
      } else {
        return (100*datum.failures/datum.requests).toFixed(2) + "%";
      }
    }

    function renderRouterSummary(routerData, routerName, $summaryEl) {
      $summaryEl.html(template(routerData));
    }

    function getRetryBudget(routerName, config) {
      if (!config) return DEFAULT_BUDGET;

      var routerObj = _.find(config.routers, function(router) {
        return router.label === routerName;
      });

      return _.get(routerObj, 'client.retries.budget.percentCanRetry', DEFAULT_BUDGET);
    }

    return function(metricsCollector, $summaryEl, $barChartEl, routerName, routerConfig) {
      var serverQuery = Query.serverQuery().allServers().withRouter(routerName).withMetrics(["load", "requests", "success", "failures"]).build();
      var clientQuery = Query.clientQuery().allClients().withRouter(routerName).withMetrics(["retries/requeues"]).build();
      var pathQuery = Query.pathQuery().allPaths().withRouter(routerName).withMetrics(["requests", "retries/total"]).build();

      var $retriesBarChart = $barChartEl.find(".retries-bar-chart");

      var retriesBarChart = new RetriesBarChart($retriesBarChart);
      var retryBudget = getRetryBudget(routerName, routerConfig);

      renderRouterSummary({ router: routerName }, routerName, $summaryEl);

      metricsCollector.registerListener(
        function(data) {
          var summaryData = processResponses(data.specific, routerName);

          retriesBarChart.update(summaryData, retryBudget);
          renderRouterSummary(summaryData, routerName, $summaryEl);
        },
        function(metrics) {
          var pathMetrics = Query.filter(pathQuery, metrics);
          var clientMetrics = Query.filter(clientQuery, metrics);
          var serverMetrics = Query.filter(serverQuery, metrics);
          return _.concat(pathMetrics, clientMetrics, serverMetrics);
        }
      );

      return {};
    };
  })();

  return RouterSummary;
});
