"use strict";

define([
  'jQuery', 'Handlebars', 'bootstrap',
  'src/metrics_collector',
  'src/routers',
  'src/process_info',
  'src/request_totals',
  'src/router_controller'
], function(
  $, Handlebars, bootstrap,
  MetricsCollector,
  Routers,
  ProcInfo,
  RequestTotals,
  RouterController
) {
  return function() {
    /**
     * Number of millis to wait between data updates.
     */
    var UPDATE_INTERVAL = 1000;

    $.get("/admin/metrics.json").done(function(metricsJson) {
      var metricsCollector = MetricsCollector(metricsJson);
      var routers = Routers(metricsJson, metricsCollector);

      var $serverData = $(".server-data");
      var buildVersion = $serverData.data("linkerd-version");
      var selectedRouter = $serverData.data("router-name");

      ProcInfo(metricsCollector, $(".proc-info"), buildVersion);
      RequestTotals(metricsCollector, selectedRouter, $(".request-totals"), _.keys(metricsJson));
      RouterController(metricsCollector, selectedRouter, routers, $(".dashboard-container"));

      $(function() {
        metricsCollector.start(UPDATE_INTERVAL);
      });
    });
  }
});

