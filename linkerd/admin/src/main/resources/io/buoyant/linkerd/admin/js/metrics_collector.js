/**
  A module to consolidate our backend metric requests. Collects all metrics that
  we need and gets them in two requests - one to metrics.json and one to metrics
  with the desired params.

  Register a listener to receive metric updates. Each listener needs to implement:
    - onMetricsUpdate(data) - to handle new incoming data
      data is of the form:
      {
        general: {}, // data obtained from /metrics.json
        specific: {} // data obtained from /metrics?m=...
      }
    - desiredMetrics() - returns a list of metrics the listener wants
*/
var MetricsCollector = (function() {
  var generalUpdateUri = "/admin/metrics.json";
  var metricsUpdateUri = "/admin/metrics?";

  function url(listeners) {
    var params = _.reduce(listeners, function(mem, listener) {
      return mem.concat(listener.desiredMetrics())
    }, []);

    return metricsUpdateUri + $.param({ m: params }, true);
  }

  function validateListeners(listeners) {
    _.each(listeners, function(listener) {
      if (!listener.onMetricsUpdate || !listener.desiredMetrics) {
        console.error("This listener needs to implement onMetricsUpdate and desiredMetrics");
      }
    });
  }

  return function(listeners) {
    validateListeners(listeners);

    function update() {
      var general = $.ajax({
        url: generalUpdateUri,
        dataType: "json",
        cache: false
      });

      var metricSpecific = $.ajax({
        url: url(listeners),
        dataType: "json",
        cache: false
      });

      $.when(general, metricSpecific).done(function(generalData, metricSpecificData) {
        var data = {
          general: generalData[0],
          specific: metricSpecificData[0]
        }

        _.each(listeners, function(listener) {
          listener.onMetricsUpdate(data);
        });
      });
    }

    return {
      start: function(interval) { setInterval(update, interval); }
    };
  };
})();
