"use strict";

define([
  'jQuery',
  'src/query',
  'src/utils',
  'src/bar_chart',
  'template/compiled_templates'
], function($,
  Query, // TODO: remove
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
      var process = function(metricName, isGauge) { return processServerResponse(data, routerName, metricName, isGauge); };
      var pathRetries = processPathResponse(data, routerName, ["retries", "total", "delta"]);
      var requeues = processClientResponse(data, routerName, ["retries", "requeues", "delta"]);

      var result = {
        router: routerName,
        load: process("load", true),
        requests: process("requests"),
        success: process("success"),
        failures: process("failures"),
        retries: (pathRetries || 0) + (requeues || 0)
      }
      var rates = getSuccessAndFailureRate(result);
      return  $.extend(result, rates);
    }

    function processServerResponse(data, routerName, metricName, isGauge) {
      var datum = _(data).get(["rt", routerName, "srv"]);
      return _.reduce(datum, function(mem, d) {
        mem += _.get(d, [metricName, isGauge ? "value" : "delta"]) || 0;
        return mem;
      }, 0);
    }

    function processClientResponse(data, routerName, metricName) {
      var datum = _(data).get(["rt", routerName, "dst", "id"]);
      return _.reduce(datum, function(mem, d) {
        mem += _.get(d, metricName) || 0;
        return mem;
      }, 0);
    }

    function processPathResponse(data, routerName, metricName) {
      var datum = _(data).get(["rt", routerName, "dst", "path"]);
      return _.reduce(datum, function(mem, d) {
        mem += _.get(d, metricName) || 0;
        return mem;
      }, 0);
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
      var serverMetrics = [{name: "load", isGauge: true}, {name: "requests"}, {name: "success"}, {name: "failures"}];
      var clientMetrics = [["retries", "requeues", "counter"]];
      var pathMetrics = [["requests", "counter"], ["retries", "total", "counter"]];

      var $retriesBarChart = $barChartEl.find(".retries-bar-chart");

      var retriesBarChart = new RetriesBarChart($retriesBarChart);
      var retryBudget = getRetryBudget(routerName, routerConfig);

      renderRouterSummary({ router: routerName }, routerName, $summaryEl);

      metricsCollector.registerListener(
        function(data) {
          var summaryData = processResponses(data.treeSpecific, routerName);

          retriesBarChart.update(summaryData, retryBudget);
          renderRouterSummary(summaryData, routerName, $summaryEl);
        },
        function(metrics, treeMetrics) {
          if (treeMetrics) {
            var raw = _.map(treeMetrics.rt, function(routerData, router) {
              var servers = _.flatMap(_.keys(routerData.srv), function(server) {
                return _.map(serverMetrics, function(metric) {
                  return ["rt", router, "srv", server, metric.name, metric.isGauge ? "gauge" : "counter"];
                });
              });

              var clients = _.flatMap(_.keys(_.get(routerData, "dst.id")), function(client) {
                return _.map(clientMetrics, function(metric) {
                  return ["rt", router, "dst", "id", client].concat(metric);
                });
              });

              var paths = _.map(_.keys(_.get(routerData, "dst.path")), function(path) {
                return _.map(pathMetrics, function(metric) {
                  return ["rt", router, "dst", "path", "svc"].concat(metric);
                });
              });

              return _.concat(servers, clients, paths);
            });
            return _.flatMap(raw);
          } else {
            return [];
          }
        }
      );

      return {};
    };
  })();

  return RouterSummary;
});
