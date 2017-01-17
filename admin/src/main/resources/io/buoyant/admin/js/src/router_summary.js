"use strict";

define([
  'jQuery',
  'Handlebars',
  'src/query',
  'src/utils',
  'text!template/router_summary.template'
], function($, Handlebars,
  Query,
  Utils,
  routerSummaryTemplate
) {
  var RouterSummary = (function() {
    var template = Handlebars.compile(routerSummaryTemplate);

    function processResponses(data, routerName) {
      var process = function(metricName) { return processResponse(data, routerName, metricName); };

      var result = {
        router: routerName,
        load: process("load"),
        requests: process("requests"),
        success: process("success"),
        failures: process("failures")
      }
      var rates = getSuccessAndFailureRate(result);
      return  $.extend(result, rates);
    }

    function processResponse(data, routerName, metricName) {
      var datum = Query.filter(Query.serverQuery().allServers().withRouter(routerName).withMetric(metricName).build(), data);
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

    return function(metricsCollector, $summaryEl, routerName) {
      var query = Query.serverQuery().allServers().withRouter(routerName).withMetrics(["load", "requests", "success", "failures"]).build();

      renderRouterSummary({ router: routerName }, routerName, $summaryEl);

      metricsCollector.registerListener(function(data) {
          var summaryData = processResponses(data.specific, routerName);
          renderRouterSummary(summaryData, routerName, $summaryEl);
        }, function(metrics) { return Query.filter(query, metrics); });

      return {};
    };
  })();

  return RouterSummary;
});
