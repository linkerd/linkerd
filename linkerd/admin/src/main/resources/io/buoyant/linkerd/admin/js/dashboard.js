"use strict";

/**
 * Number of millis to wait between data updates.
 */
var UPDATE_INTERVAL = 1000;

$.when(
  $.get("/admin/metrics.json")
).done(function(metricsJson) {

  $(function() {
    var dashboard = Dashboard();
    dashboard.start(UPDATE_INTERVAL);
  });
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
