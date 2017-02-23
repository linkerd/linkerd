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

    function getTreeDeltaPayload(metricNames, resp, prevResp) {
      _.each(metricNames, function(metric) {
        if(_.isArray(metric)) {
          // console.log(metric);
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

    return function(initialMetrics, initialTreeMetrics) {
      var prevMetrics = initialMetrics;
      var prevTreeMetrics = initialTreeMetrics;

      function update(resp, treeResp) {
        var defaultMetrics = _.keys(resp);
        var specific = generateDeltaPayload(resp, defaultMetrics, prevMetrics);

        var metricsToGet = _.flatMap(listeners, function(l) { return l.metrics(); });
        var treeSpecific = getTreeDeltaPayload(metricsToGet, treeResp, prevTreeMetrics);

        prevMetrics = resp;
        prevTreeMetrics = treeResp;

        _.each(listeners, function(listener) {
          var metricNames = listener.metrics();
          var data = {
            general: resp,
            specific: _.filter(specific, function(d) {return _.includes(metricNames, d.name);}),
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
        getCurrentMetrics: function() { return _.keys(prevMetrics); },
        registerListener: registerListener,
        deregisterListener: deregisterListener,
        __update__: update
      };
    };
  })();

  return MetricsCollector;
});

