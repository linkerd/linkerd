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

    function getTreeDeltaPayload(metricNames, resp, prevResp) {
      _.each(metricNames, function(metric) {
        if(_.isArray(metric)) {
          var prevValue = _.get(prevResp, metric);
          var currentValue = _.get(resp, metric);
          if (prevValue !== undefined && currentValue !== undefined) {
            _.set(resp, _.take(metric, metric.length - 1).concat(["delta"]), currentValue - prevValue);
            _.set(resp, _.take(metric, metric.length - 1).concat(["value"]), currentValue);
          }
        }
      });
      return resp;
    }

    return function(initialTreeMetrics) {
      var prevTreeMetrics = initialTreeMetrics;

      function update(resp, treeResp) {
        var defaultMetrics = _.keys(resp);

        var metricsToGet = _.flatMap(listeners, function(l) { return l.metrics(resp, treeResp); }); // remove resp when done
        var treeSpecific = getTreeDeltaPayload(metricsToGet, treeResp, prevTreeMetrics);

        prevTreeMetrics = treeResp;

        _.each(listeners, function(listener) {
          var metricNames = listener.metrics(null, prevTreeMetrics); // TODO: remove  null when done
          var data = {
            general: resp,
            treeSpecific: treeSpecific
          }
          listener.handler(data);
        });
      }

      return {
        start: function(interval) {
          $.when($.get(generalUpdateUri), $.get(generalUpdateUri+"?tree=1")).done(function(r1, r2) {
            update(r1[0], r2[0]);
          });
          setInterval(function(){
            $.when($.get(generalUpdateUri), $.get(generalUpdateUri+"?tree=1")).done(function(r1, r2) {
            update(r1[0], r2[0]);
          });
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

