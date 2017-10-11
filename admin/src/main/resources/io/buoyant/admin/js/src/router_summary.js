"use strict";

define([
  'jQuery',
  'src/utils',
  'src/bar_chart',
  'template/compiled_templates'
], function(
  $,
  Utils,
  BarChart,
  templates
) {
  var RetriesBarChart = function($container) {
    function displayPercent(percent) {
      return _.isNumber(percent) ? Math.round(percent * 100) + "%" : " - ";
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

    function getMetrics(routerName) {
      var serverAccessor = ["rt", routerName, "server"];
      var clientAccessor = ["rt", routerName, "client"];
      var pathAccessor = ["rt", routerName, "service"];

      return _.each({
        load: {
          metricKey: ["load", "gauge"],
          accessor: serverAccessor,
          isGauge: true,
        },
        requests: {
          metricKey: ["requests", "counter"],
          accessor: serverAccessor,
        },
        success: {
          metricKey: ["success", "counter"],
          accessor: serverAccessor,
        },
        failures: {
          metricKey: ["failures", "counter"],
          accessor: serverAccessor,
        },
        requeues: {
          metricKey: ["retries", "requeues", "counter"],
          accessor: clientAccessor
        },
        pathRetries: {
          metricKey: ["retries", "total", "counter"],
          accessor: pathAccessor
        },
      }, function(defn) {
        var key = _.clone(defn.metricKey);
        key[key.length - 1] = defn.isGauge ? "gauge" : "delta";
        defn.metricAccessor = key;
      });
    }

    function processResponses(data, routerName, metrics) {
      function summarize(metric) {
        var m = metrics[metric];
        var datum = _(data).get(m.accessor);

        return _.reduce(datum, function(totalValue, entityData) {
          totalValue += _.get(entityData, m.metricAccessor) || 0;
          return totalValue;
        }, 0);
      }

      var result = {
        router: routerName,
        load: summarize("load", true),
        requests: summarize("requests"),
        success: summarize("success"),
        failures: summarize("failures"),
        retries: summarize("pathRetries") + summarize("requeues")
      }
      var rates = getSuccessAndFailureRate(result);
      return  $.extend(result, rates);
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
        if (router.label) {
            return router.label === routerName;
        } else {
            return router.protocol == routerName;
        }

      });

      return _.get(routerObj, 'service.retries.budget.percentCanRetry', DEFAULT_BUDGET);
    }

    return function(metricsCollector, $summaryEl, $barChartEl, routerName, routerConfig) {
      var $retriesBarChart = $barChartEl.find(".retries-bar-chart");

      var retriesBarChart = new RetriesBarChart($retriesBarChart);
      var retryBudget = getRetryBudget(routerName, routerConfig);

      var routerMetrics = getMetrics(routerName);

      renderRouterSummary({ router: routerName }, routerName, $summaryEl);

      metricsCollector.registerListener("RouterSummary_" + routerName, metricsHandler);

      function metricsHandler(data) {
        var summaryData = processResponses(data, routerName, routerMetrics);

        retriesBarChart.update(summaryData, retryBudget);
        renderRouterSummary(summaryData, routerName, $summaryEl);
      }

      return {};
    };
  })();

  return RouterSummary;
});
