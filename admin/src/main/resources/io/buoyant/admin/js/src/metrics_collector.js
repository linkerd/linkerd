"use strict";
/**
  A module to consolidate our backend metric requests. Collects all metrics that
  we need and gets them in two requests - one to metrics.json and one to metrics
  with the desired params.
*/
define(['jQuery'], function($) {

  var MetricsCollector = (function() {
    var generalUpdateUri = "admin/metrics.json";
    var listeners = [];
    /**
      Register a listener to receive metric updates.
      handler: function called with incoming data of the form:
        {
          general: {}, // data obtained from /metrics.json
          specific: {} // deltas derived from current and previous /metrics.json calls
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

    function generateDeltaPayload(generalData, defaultMetrics, prevMetrics) {
      var metrics = _(listeners)
        .flatMap(function(listener){ return listener.metrics(defaultMetrics); })
        .uniq()
        .compact()
        .value();

      return _.flatMap(metrics, function(metricName) {
        var prevValue = prevMetrics[metricName];
        var currentValue = generalData[metricName];
        if (prevValue !== undefined && currentValue !== undefined) {
          return [{
            name: metricName,
            delta: currentValue - prevValue,
            value: currentValue
          }];
        } else {
          return [];
        }
      })

    }

    return function(initialMetrics) {
      var prevMetrics = initialMetrics;

      function update(resp) {
        var defaultMetrics = _.keys(resp);
        var specific = generateDeltaPayload(resp, defaultMetrics, prevMetrics);
        prevMetrics = resp;

        _.each(listeners, function(listener) {
          var metricNames = listener.metrics(defaultMetrics);
          var data = {
            general: resp,
            specific: _.filter(specific, function(d) {return _.includes(metricNames, d.name);})
          }
          listener.handler(data);
        });
      }

      return {
        start: function(interval) {
          $.get(generalUpdateUri, update)
          setInterval(function(){$.get(generalUpdateUri, update)}, interval);
        },
        getCurrentMetrics: function() { return _.keys(prevMetrics); },
        registerListener: registerListener,
        deregisterListener: deregisterListener,
        __update__: update
      };
    };
  })();

  return MetricsCollector;
});

