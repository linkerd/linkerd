"use strict";
/**
  A module to consolidate our backend metric requests. Collects all metrics that
  we need.
*/
define(['jQuery'], function($) {

  var MetricsCollector = (function() {
    var updateUri = "admin/metrics.json?tree=1";
    var listeners = [];
    /**
      Register a listener to receive metric updates.
      handler: function called with incoming tree data
    */
    function registerListener(handler) {
      listeners.push({handler: handler});
    }

    function deregisterListener(handler) {
      _.remove(listeners, function(l) { return l.handler === handler; });
    }

    function calculateDeltas(resp, prevResp, path) {
      // modifies resp!
      _.each(resp, function(v, k) {
        if (k === "counter") {
          var prevValue = _.get(prevResp, k);
          var currentValue = _.get(resp, k);

          if (prevValue !== undefined && currentValue !== undefined) {
            _.set(resp, "delta", currentValue - prevValue);
          }
        } else {
          calculateDeltas(resp[k], prevResp[k]);
        }
      });
    }

    return function(initialMetrics) {
      var prevMetrics = initialMetrics;

      function update(resp) {
        calculateDeltas(resp, prevMetrics, []);
        prevMetrics = resp;

        _.each(listeners, function(listener) {
          listener.handler(resp);
        });
      }

      return {
        start: function(interval) {
          $.get(updateUri).done(update);

          setInterval(function(){
            $.get(updateUri).done(update);
          }, interval);
        },
        registerListener: registerListener,
        deregisterListener: deregisterListener,
        __update__: update
      };
    };
  })();

  return MetricsCollector;
});

