"use strict";

define([
  'jQuery',
  'Handlebars',
  'src/query',
  'src/utils',
  'src/bar_chart'
  // 'text!template/router_stats.template'
], function($, Handlebars,
  Query,
  Utils,
  BarChart
  // routerStatsTemplate
) {
  var RouterStats = (function() {
    // var template = Handlebars.compile(routerStatsTemplate);

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
      if (percent === null) return "";
      else if (percent < 0.1) return "green";
      else if (percent < 0.5) return "yellow";
      else if (percent < 0.75) return "orange";
      else return "red";
    }

    function getBarChartPercent(percent) {
      // var budgetRemaining = 0.2 // budgetPercent - retriesPercent
      return {
        percent: percent,
        label: {
          description: "Retry percentage",
          value: _.isNull(percent) ? "-" : Math.round(percent * 100) + "%"
        }
      }
    }

    function getRetryPercent(data) {
      var numer = (data["retries/requeues"] || 0) + (data["retries/total"] || 0);
      var denom = data["requests"];

      return !denom ? null : (numer || 0) / denom;
    }

    function render(percent, $statsContainer) {
      var percentDisplay = _.isNull(percent) ? "-" : Math.round(percent * 100) + "%";
      $statsContainer.html("Retry percentage: " + percentDisplay);
    }

    return function(metricsCollector, $containerEl, routerName) {
      var clientQuery = Query.clientQuery().allClients().withRouter(routerName).withMetrics(["retries/requeues"]).build();
      var pathQuery = Query.pathQuery().allPaths().withRouter(routerName).withMetrics(["requests", "retries/total"]).build();

      var $statsContainer = $containerEl.find(".retries-stats");
      var $retriesBarChart = $containerEl.find(".retries-bar-chart");
      var retriesBarChart = new BarChart($retriesBarChart, getBarChartColor);

      metricsCollector.registerListener(function(data) {
          var summaryData = processResponses(data.specific, routerName);
          var retryPercent = getRetryPercent(summaryData);

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
