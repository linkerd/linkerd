"use strict";

define([
  'jQuery', 'bootstrap',
  'src/router_services',
  'src/metrics_collector',
  'src/process_info',
  'src/request_totals',
  'src/router_controller'
], function(
  $, bootstrap,
  RouterServices,
  MetricsCollector,
  ProcInfo,
  RequestTotals,
  RouterController
) {
  return function(routerConfig, isServiceMap) {
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
        mem[router]["services"] = _.keys(_.get(data, "service"));
        return mem;
      }, {});

      var $serverData = $(".server-data");
      var buildVersion = $serverData.data("linkerd-version");
      var selectedRouter = $serverData.data("router-name");

      ProcInfo(metricsCollector, $(".proc-info"), buildVersion);
      RequestTotals(metricsCollector, selectedRouter, $(".request-totals"));

      if (isServiceMap) {
        RouterServices(metricsCollector, initialData, $(".service-dashboard-container"));
      } else {
        RouterController(metricsCollector, selectedRouter, initialData, $(".client-dashboard-container"), routerConfig);
      }

      $(function() {
        metricsCollector.start(UPDATE_INTERVAL, initialData);
      });
    });
  }
});

