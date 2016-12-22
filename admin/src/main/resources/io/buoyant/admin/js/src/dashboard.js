"use strict";

define([
  'jQuery', 'Handlebars', 'bootstrap',
  'src/metrics_collector',
  'src/routers',
  'src/process_info',
  'src/request_totals',
  'src/router_controller',
  'text!template/router_container.template',
  'text!template/router_server.template',
  'text!template/router_client.template',
  'text!template/router_client_container.template',
  'text!template/router_server_container.template',
  'text!template/server_rate_metric.partial.template',
  'text!template/metric.partial.template',
  'text!template/router_summary.template',
  'text!template/process_info.template',
  'text!template/request_totals.template'
], function(
  $, Handlebars, bootstrap,
  MetricsCollector,
  Routers,
  ProcInfo,
  RequestTotals,
  RouterController,
  routerContainerTemplate,
  routerServerTemplate,
  routerClientTemplate,
  routerClientContainerTemplate,
  routerServerContainerTemplate,
  serverMetricPartialTemplate,
  metricPartialTemplate,
  routerSummaryTemplate,
  overviewStatsTemplate,
  requestTotalsTemplate
) {
  return function() {
    /**
     * Number of millis to wait between data updates.
     */
    var UPDATE_INTERVAL = 1000;

    $.get("/admin/metrics.json").done(function(metricsJson) {
      var routerTemplates = {
        summary: Handlebars.compile(routerSummaryTemplate),
        container: Handlebars.compile(routerContainerTemplate),
        server: Handlebars.compile(routerServerTemplate),
        client: Handlebars.compile(routerClientTemplate),
        clientContainer: Handlebars.compile(routerClientContainerTemplate),
        serverContainer: Handlebars.compile(routerServerContainerTemplate),
        serverMetric: Handlebars.compile(serverMetricPartialTemplate),
        metric: Handlebars.compile(metricPartialTemplate)
      }

      var metricsCollector = MetricsCollector(metricsJson);
      var routers = Routers(metricsJson, metricsCollector);

      var $serverData = $(".server-data");
      var buildVersion = $serverData.data("linkerd-version");
      var selectedRouter = $serverData.data("router-name");
      ProcInfo(metricsCollector, $(".proc-info"), Handlebars.compile(overviewStatsTemplate), buildVersion);
      RequestTotals(metricsCollector, selectedRouter, $(".request-totals"), Handlebars.compile(requestTotalsTemplate), _.keys(metricsJson));
      RouterController(metricsCollector, selectedRouter, routers, routerTemplates, $(".dashboard-container"));

      $(function() {
        metricsCollector.start(UPDATE_INTERVAL);
      });
    });
  }
});

