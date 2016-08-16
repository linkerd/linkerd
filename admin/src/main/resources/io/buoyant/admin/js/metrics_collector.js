/* exported MetricsCollector */
/**
  A module to consolidate our backend metric requests. Collects all metrics that
  we need and gets them in two requests - one to metrics.json and one to metrics
  with the desired params.
*/
var MetricsCollector = (function() {
  var generalUpdateUri = "/admin/metrics.json";
  var metricsUpdateUri = "/admin/metrics";
  var listeners = [];

  function requestBody(listeners, defaultMetrics) {
    var params = _(listeners)
      .map(function(listener){ return listener.metrics(defaultMetrics); })
      .flatten()
      .uniq()
      .value();
    return $.param({ m: params }, true);
  }

  /**
    Register a listener to receive metric updates.
    handler: function called with incoming data of the form:
      {
        general: {}, // data obtained from /metrics.json
        specific: {} // data obtained from /metrics?m=...
      }
    metrics: returns a list of metrics the listener wants.
      Called with a list of metric names to choose from.
  */
  function registerListener(handler, metrics) {
    listeners.push({handler: handler, metrics: metrics});
  }

  function deregisterListener(handler) {
    _.remove(listeners, function(l) { return l.handler === handler; });
  }

  return function(initialMetrics) {
    var defaultMetrics = _.keys(initialMetrics);

    function update() {
      var general = $.ajax({
        url: generalUpdateUri,
        dataType: "json",
        cache: false
      });

      var metricSpecific = $.ajax({
        url: metricsUpdateUri,
        type: "POST",
        data: requestBody(listeners, defaultMetrics),
        dataType: "json",
        cache: false
      });

      $.when(general, metricSpecific).done(function(generalData, metricSpecificData) {
        var data = {
          general: generalData[0],
          specific: metricSpecificData[0]
        }

        defaultMetrics = _.keys(data.general);

        _.each(listeners, function(listener) {
          listener.handler(data);
        });
      });
    }

    return {
      start: function(interval) {
        update();
        setInterval(update, interval);
      },
      getCurrentMetrics: function() { return defaultMetrics; },
      registerListener: registerListener,
      deregisterListener: deregisterListener
    };
  };
})();
