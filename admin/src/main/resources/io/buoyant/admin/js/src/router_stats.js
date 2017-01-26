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
      if (percent < 0.1) return "green";
      else if (percent < 0.5) return "yellow";
      else if (percent < 0.75) return "orange";
      else return "red";
    }

    function getBarChartPercent(data) {
      var numer = (data["retries/requeues"] || 0) + (data["retries/total"] || 0);
      var denom = data["requests"];
      var percent = !denom ? null : (numer || 0) / denom;

      return {
        percent: percent,
        label: {
          description: "Retry percentage",
          value: _.isNull(percent) ? "-" : Math.round(percent * 100) + "%"
        }
      }
    }

    return function(metricsCollector, $containerEl, routerName) {
      var clientQuery = Query.clientQuery().allClients().withRouter(routerName).withMetrics(["retries/requeues"]).build();
      var pathQuery = Query.pathQuery().allPaths().withRouter(routerName).withMetrics(["requests", "retries/total"]).build();

      var $retriesBarChart = $containerEl.find(".retries-bar-chart");
      var retriesBarChart = new BarChart($retriesBarChart, getBarChartPercent, getBarChartColor);

      metricsCollector.registerListener(function(data) {
          var summaryData = processResponses(data.specific, routerName);
          retriesBarChart.update(summaryData);
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
