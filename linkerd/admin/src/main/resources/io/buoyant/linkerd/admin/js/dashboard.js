"use strict";

/**
 * Number of millis to wait between data updates.
 */
var UPDATE_INTERVAL = 1000;

$.when(
  $.get("/files/template/process_info.template"),
  $.get("/admin/metrics.json")
).done(function(overviewStatsRsp, metricsJson) {
  appendOverviewSection();
  var procInfo = ProcInfo();

  $(function() {
    var dashboard = Dashboard();
    procInfo.start(UPDATE_INTERVAL);
    dashboard.start(UPDATE_INTERVAL);
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
    var json = $.parseJSON(data);
    $(".test-div").text("Fill in content.");
  }

  /**
   * Returns a function that may be called to trigger an update.
   */
  return function() {

    function update() {
      $.ajax({
        url: "/admin/metrics.json",
        dataType: "text",
        cache: false,
        success: function(metrics) {
          render(metrics);
        }
      });
    }

    return {
      start: function(interval) { setInterval(update, interval); }
    };
  };
})();
