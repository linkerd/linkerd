var RouterSummary = (function() {
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
    var datum = Query.find(Query.clientQuery().withRouter(routerName).withMetric(metricName).build(), data);
    return !datum ? 0 : datum.delta;
  }

  function getSuccessAndFailureRate(result) {
    var successRate = new SuccessRate(result.requests, result.success, result.failures);

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

  function renderRouterSummary(routerData, template, routerName, $summaryEl) {
    $summaryEl.html(template(routerData));
  }

  return function(metricsCollector, summaryTemplate, $summaryEl, routerName) {
    var query = Query.clientQuery().withRouter(routerName).withMetrics(["load", "requests", "success", "failures"]).build();

    renderRouterSummary({ router: routerName }, summaryTemplate, routerName, $summaryEl);

    metricsCollector.registerListener(function(data) {
        var summaryData = processResponses(data.specific, routerName);
        renderRouterSummary(summaryData, summaryTemplate, routerName, $summaryEl);
      }, function(metrics) { return Query.filter(query, metrics); });

    return {};
  };
})();
