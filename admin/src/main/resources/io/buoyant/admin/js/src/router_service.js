"use strict";

define([
  'jQuery',
  'template/compiled_templates',
  'bootstrap'
], function($, templates) {

  function render(svcClientMetrics, $container) {
    var clientsHtml = templates.router_service_metrics({
      clients: svcClientMetrics
    });

    $container.html(clientsHtml);
  }

  return function(metricsCollector, $routerContainer, service) {
    var $svcContainer = $(templates.router_service_container({
      service: service
    }));
    $routerContainer.append($svcContainer);

    var $metricsContainer = $($svcContainer.find(".svc-metrics")[0]);

    function onMetricsUpdate(metrics) {
      render(metrics, $metricsContainer);
    }

    return {
      onMetricsUpdate: onMetricsUpdate
    }
  };
});
