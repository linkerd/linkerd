"use strict";

define([
  'jQuery', 'bootstrap',
  'src/metrics_collector',
  'src/process_info',
  'src/request_totals',
  'src/router_controller'
], function(
  $, bootstrap,
  MetricsCollector,
  ProcInfo,
  RequestTotals,
  RouterController
) {
  return function(routerConfig) {
    /**
     * Number of millis to wait between data updates.
     */
    var UPDATE_INTERVAL = 1000;

    $.get("admin/metrics.json?tree=1").done(function(metricsJson) {
      var metricsCollector = MetricsCollector(metricsJson);

      var initialRouters = _.get(metricsJson, "rt");
      var initialData = _.reduce(initialRouters, function(mem, data, router) {
        mem[router] = {};
        mem[router]["servers"] = _.keys(data.server);
        mem[router]["clients"] = _.keys(_.get(data, "client"));
        return mem;
      }, {});

      var $serverData = $(".server-data");
      var buildVersion = $serverData.data("linkerd-version");
      var selectedRouter = $serverData.data("router-name");

      ProcInfo(metricsCollector, $(".proc-info"), buildVersion);
      RequestTotals(metricsCollector, selectedRouter, $(".request-totals"));
      RouterController(metricsCollector, selectedRouter, initialData, $(".dashboard-container"), routerConfig);

      $(function() {
        metricsCollector.start(UPDATE_INTERVAL, initialData);
      });
    });
  }
});

