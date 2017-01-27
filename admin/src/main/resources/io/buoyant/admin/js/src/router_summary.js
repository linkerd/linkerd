"use strict";

define([
  'jQuery',
  'Handlebars',
  'src/query',
  'src/utils',
  'src/bar_chart',
  'text!template/router_summary.template'
], function($, Handlebars,
  Query,
  Utils,
  BarChart,
  routerSummaryTemplate
) {
  var RouterSummary = (function() {
    var CONFIGURED_BUDGET = 0.2 // default 20% TODO get from router config
    var template = Handlebars.compile(routerSummaryTemplate);

    function displayPercent(percent) {
      return _.isNull(percent) ? " - " : Math.round(percent * 100) + "%";
    }

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

    function getBarChartColor(percent) {
      if (percent < 0.5) return "red";
      else if (percent < 0.75) return "orange";
      else return "green";
    }

    function getBarChartPercent(data) {
      var retryPercent = !data["requests"] ? null : (data["retries"] || 0) / data["requests"];
      var budgetRemaining = Math.max(CONFIGURED_BUDGET - (retryPercent || 0), 0);
      var healthBarPercent = Math.min(budgetRemaining / CONFIGURED_BUDGET, 1);

      return {
        percent: healthBarPercent,
        label: {
          description: "Retry budget available",
          value: displayPercent(budgetRemaining) + " / " + displayPercent(CONFIGURED_BUDGET)
        },
        warningLabel: retryPercent < CONFIGURED_BUDGET ? null : "budget exhausted"
      }
    }

    function renderRouterSummary(routerData, routerName, $summaryEl) {
      $summaryEl.html(template(routerData));
    }

    return function(metricsCollector, $summaryEl, $barChartEl, routerName) {
      var serverQuery = Query.serverQuery().allServers().withRouter(routerName).withMetrics(["load", "requests", "success", "failures"]).build();
      var clientQuery = Query.clientQuery().allClients().withRouter(routerName).withMetrics(["retries/requeues"]).build();
      var pathQuery = Query.pathQuery().allPaths().withRouter(routerName).withMetrics(["requests", "retries/total"]).build();

      var $retriesBarChart = $barChartEl.find(".retries-bar-chart");
      var retriesBarChart = new BarChart($retriesBarChart, getBarChartColor);

      renderRouterSummary({ router: routerName }, routerName, $summaryEl);

      metricsCollector.registerListener(
        function(data) {
          var summaryData = processResponses(data.specific, routerName);

          retriesBarChart.update(getBarChartPercent(summaryData));
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
