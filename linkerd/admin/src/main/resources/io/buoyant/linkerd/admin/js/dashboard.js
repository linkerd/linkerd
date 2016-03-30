"use strict";

/**
 * Number of millis to wait between data updates.
 */
var UPDATE_INTERVAL = 1000;

$.when(
  $.get("/files/template/router_container.template"),
  $.get("/files/template/router_summary.template"),
  $.get("/files/template/process_info.template"),
  $.get("/files/template/request_totals.template"),
  $.get("/admin/metrics.json")
).done(function(routerContainerRsp, routerSummaryRsp, overviewStatsRsp, requestTotalsRsp, metricsJson) {
  appendOverviewSection();

  var selectedRouter = getSelectedRouter(); // TODO: update this to avoid passing params in urls #198
  var routers = Routers(metricsJson[0]);
  var routerTemplates = {
    summary: Handlebars.compile(routerSummaryRsp[0]),
    container: Handlebars.compile(routerContainerRsp[0])
  }

  var procInfo = ProcInfo();
  var dashboard = Dashboard();
  var requestTotals = RequestTotals($(".request-totals"), Handlebars.compile(requestTotalsRsp[0]), _.keys(metricsJson[0]));
  var routerDisplays = RouterController(selectedRouter, routers, routerTemplates, $(".dashboard-container"));

  var metricsListeners = [procInfo, dashboard, requestTotals, routerDisplays];
  var metricsCollector = MetricsCollector(metricsListeners);

  $(function() {
    metricsCollector.start(UPDATE_INTERVAL);
  });

  function appendOverviewSection() {
    var overviewTemplate = Handlebars.compile(overviewStatsRsp[0]);
    var compiledHtml = overviewTemplate(getOverviewStatsData());

    var $overviewStats = $('<div />').html(compiledHtml);
    $overviewStats.appendTo("body");
  }

  function getOverviewStatsData() {
    // todo: move to process_info.js after old dashboard is killed #198
    var buildVersion = $(".server-data").data("linkerd-version");

    return {
      stats: [
        { description: "linkerd version", dataKey: "",  elemId: "linkerd-version", value: buildVersion },
        { description: "uptime", dataKey: "jvm/uptime",  elemId: "jvm-uptime", value: "0s" },
        { description: "thread count", dataKey: "jvm/thread/count", elemId: "jvm-thread-count", value: "0" },
        { description: "memory used", dataKey: "jvm/mem/current/used", elemId: "jvm-mem-current-used", value: "0MB" },
        { description: "gc", dataKey: "jvm/gc/msec",  elemId: "jvm-gc-msec", value: "1ms" }
      ]
    }
  }
});

var Dashboard = (function() {

  function render(data) {
  }

  return function() {
    return {
      onMetricsUpdate: function(data) {
        render(data.general);
      },
      desiredMetrics: function() {
        return [];
      }
    };
  };
})();
