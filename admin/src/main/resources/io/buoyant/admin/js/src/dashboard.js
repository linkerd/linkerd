"use strict";

define([
  'jQuery', 'bootstrap',
  'src/metrics_collector',
  'src/routers',
  'src/process_info',
  'src/request_totals',
  'src/router_controller'
], function(
  $, bootstrap,
  MetricsCollector,
  Routers,
  ProcInfo,
  RequestTotals,
  RouterController
) {
  return function(routerConfig) {
    /**
     * Number of millis to wait between data updates.
     */
    var UPDATE_INTERVAL = 1000;

    $.when(
      $.get("admin/metrics.json"),
      $.get("admin/metrics.json?tree=1")
    )
    .done(function(r1, r2) {
      var metricsJson = r1[0];
      var metricsJsonTree = r2[0];
      var metricsCollector = MetricsCollector(metricsJson, metricsJsonTree);
      var routers = Routers(metricsJson, metricsCollector);

      var $serverData = $(".server-data");
      var buildVersion = $serverData.data("linkerd-version");
      var selectedRouter = $serverData.data("router-name");

      ProcInfo(metricsCollector, $(".proc-info"), buildVersion);
      RequestTotals(metricsCollector, selectedRouter, $(".request-totals"), _.keys(metricsJson));
      RouterController(metricsCollector, selectedRouter, routers, $(".dashboard-container"), routerConfig);

      $(function() {
        metricsCollector.start(UPDATE_INTERVAL);
      });
    });
  }
});

