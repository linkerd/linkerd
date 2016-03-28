var RouterSummary = (function() {
  var summaryKeys = ["load", "requests", "success", "failures"];

  function addClients(clients, metricsParams, routerName) {
    var routerClients = _.filter(clients, ["router", routerName]);
    metricsParams = metricsParams.concat(clientsToMetricParam(routerClients));
  }

  function clientsToMetricParam(clients) {
    return _(clients).map(function(client) {
      return _.map(summaryKeys, function(key) {
        return client.prefix + key;
      });
    }).flatten().value();
  }

  function processResponses(data) {
    var result = {
      router: data.router,
      load: data.load.delta,
      requests: data.requests.delta,
      success: !data.success ? 0 : data.success.delta,
      failures: !data.failures ? 0 : data.failures.delta
    }
    var rates = getSuccessAndFailureRate(result);
    return  $.extend(result, rates);
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

  return function(routers, summaryTemplate, $summaryEl, routerName) {
    var metricsParams = clientsToMetricParam(routers.clients(routerName));
    routers.onAddedClients(addClients, metricsParams, routerName);
    renderRouterSummary({ router: routerName }, summaryTemplate, routerName, $summaryEl);

    return {
      onMetricsUpdate: function(data) {
        var summaryData = processResponses(data);
        renderRouterSummary(summaryData, summaryTemplate, routerName, $summaryEl);
      },
      desiredMetrics: function() { return metricsParams; },
      getRouterName: function() { return routerName; }
    };
  };
})();
