"use strict";

define([
  'jQuery', 'bootstrap',
  'src/metrics_collector',
  'src/process_info',
  'src/request_totals',
  'src/router_service_dashboard',
  'src/router_client_dashboard'
], function(
  $, bootstrap,
  MetricsCollector,
  ProcInfo,
  RequestTotals,
  RouterServiceDashboard,
  RouterClientDashboard
) {
  return function(routerConfig, dashboardType) {
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

      if (dashboardType === "service") {
        RouterServiceDashboard(metricsCollector, initialData, $(".service-dashboard-container"));
      } else {
        RouterClientDashboard(metricsCollector, selectedRouter, initialData, $(".client-dashboard-container"), routerConfig);
      }

      $(function() {
        metricsCollector.start(UPDATE_INTERVAL, initialData);
      });
    });
  }
});

