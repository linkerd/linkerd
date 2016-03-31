"use strict";

/**
 * Number of millis to wait between data updates.
 */
var UPDATE_INTERVAL = 1000;

$.when(
  $.get("/files/template/router_server.template"),
  $.get("/files/template/router_container.template"),
  $.get("/files/template/router_summary.template"),
  $.get("/files/template/process_info.template"),
  $.get("/files/template/request_totals.template"),
  $.get("/admin/metrics.json")
).done(function(routerServerRsp, routerContainerRsp, routerSummaryRsp, overviewStatsRsp, requestTotalsRsp, metricsJson) {
  var selectedRouter = getSelectedRouter(); // TODO: update this to avoid passing params in urls #198
  var routers = Routers(metricsJson[0]);
  var routerTemplates = {
    summary: Handlebars.compile(routerSummaryRsp[0]),
    container: Handlebars.compile(routerContainerRsp[0]),
    server: Handlebars.compile(routerServerRsp[0])
  }

  var metricsCollector = MetricsCollector(metricsJson[0]);

  var buildVersion = $(".server-data").data("linkerd-version");
  var procInfo = ProcInfo(metricsCollector, $(".proc-info"), Handlebars.compile(overviewStatsRsp[0]), buildVersion);
  var requestTotals = RequestTotals(metricsCollector, $(".request-totals"), Handlebars.compile(requestTotalsRsp[0]), _.keys(metricsJson[0]));
  var routerDisplays = RouterController(metricsCollector, selectedRouter, routers, routerTemplates, $(".dashboard-container"));

  $(function() {
    metricsCollector.start(UPDATE_INTERVAL);
  });
});

