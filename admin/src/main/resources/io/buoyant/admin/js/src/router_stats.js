"use strict";

define([
  'jQuery',
  'Handlebars',
  'src/query',
  'src/utils',
  'src/bar_chart'
], function($, Handlebars,
  Query,
  Utils,
  BarChart
) {
  var RouterStats = (function() {
    var CONFIGURED_BUDGET = 0.2 // default 20% TODO get from router config

    function displayPercent(percent) {
      return _.isNull(percent) ? " - " : Math.round(percent * 100) + "%";
    }

    function processResponses(data, routerName) {
      var result = {
        router: routerName,
        "retries/requeues": processClientResponse(data, routerName, "retries/requeues"),
        requests: processPathResponse(data, routerName, "requests"),
        "retries/total": processPathResponse(data, routerName, "retries/total")
      }
      return result;
    }

    function processClientResponse(data, routerName, metricName) {
      var datum = Query.filter(Query.clientQuery().allClients().withRouter(routerName).withMetric(metricName).build(), data);
      return _.sumBy(datum, "delta");
    }

    function processPathResponse(data, routerName, metricName) {
      var q = Query.pathQuery().allPaths().withRouter(routerName).withMetric(metricName).build();
      var datum = Query.filter(q, data);
      return _.sumBy(datum, "delta");
    }

    function getBarChartColor(percent) {
      if (percent < 0.5) return "red";
      else if (percent < 0.75) return "orange";
      else return "green";
    }

    function getBarChartPercent(retryPercent) {
      var budgetRemaining = Math.max(CONFIGURED_BUDGET - (retryPercent || 0), 0);
      var healthBarPercent = Math.min(budgetRemaining / CONFIGURED_BUDGET, 1);

      return {
        percent: healthBarPercent,
        label: {
          description: "Retry budget available",
          value: displayPercent(budgetRemaining) + " / " + displayPercent(CONFIGURED_BUDGET)
        },
        // secondLabel: {
        //   description: "Retry percentage",
        //   value: Math.round(retryPercent * 100) + "%"
        // },
        warningLabel: retryPercent < CONFIGURED_BUDGET ? null : "budget exhausted"
      }
    }

    function getRetryPercent(data) {
      var numer = (data["retries/requeues"] || 0) + (data["retries/total"] || 0);
      var denom = data["requests"];

      return !denom ? null : (numer || 0) / denom;
    }

    function render(percent, $statsContainer) {
      if(percent > CONFIGURED_BUDGET) {
        // $statsContainer.html('<div class="warning text-center">Retry budget exhausted</div>');
      } else {
        // $statsContainer.empty();
      }
    }

    return function(metricsCollector, $containerEl, routerName) {
      var clientQuery = Query.clientQuery().allClients().withRouter(routerName).withMetrics(["retries/requeues"]).build();
      var pathQuery = Query.pathQuery().allPaths().withRouter(routerName).withMetrics(["requests", "retries/total"]).build();

      var $statsContainer = $containerEl.find(".retries-stats");
      var $retriesBarChart = $containerEl.find(".retries-bar-chart");

      var retriesBarChart = new BarChart($retriesBarChart, getBarChartColor);

      metricsCollector.registerListener(function(data) {
          var summaryData = processResponses(data.specific, routerName);
          // var retryPercent = getRetryPercent(summaryData);
          var retryPercent = 0.1;

          retriesBarChart.update(getBarChartPercent(retryPercent));
          render(retryPercent, $statsContainer);
        }, function(metrics) {
          var pathMetrics = Query.filter(pathQuery, metrics);
          var clientMetrics = Query.filter(clientQuery, metrics);
          return _.concat(pathMetrics, clientMetrics);
        });

      return {};
    };
  })();

  return RouterStats;
});
