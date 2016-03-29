"use strict";

/**
 * Number of millis to wait between data updates.
 */
var UPDATE_INTERVAL = 1000;

$.when(
  $.get("/files/template/process_info.template"),
  $.get("/files/template/request_totals.template"),
  $.get("/admin/metrics.json")
).done(function(overviewStatsRsp, requestTotalsRsp, metricsJson) {
  appendOverviewSection();

  var routers = Routers(metricsJson[0]);

  var procInfo = ProcInfo();
  var dashboard = Dashboard();
  var requestTotals = RequestTotals($(".request-totals"), Handlebars.compile(requestTotalsRsp[0]), routers);

  var metricsListeners = [procInfo, dashboard, requestTotals];
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
    $(".test-div").text("Fill in content.");
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
