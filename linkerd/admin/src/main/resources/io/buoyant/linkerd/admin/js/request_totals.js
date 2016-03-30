"use strict";

var RequestTotals = (function() {
  const SERVER = "server";
  const CLIENT = "client";


  var metricDefinitions = [
    {
      description: "Current requests",
      metricSuffix: "requests",
      metricType: SERVER
    },
    {
      description: "Pending",
      metricSuffix: "load",
      metricType: SERVER
    },
    {
      description: "Incoming Connections",
      metricSuffix: "connections",
      metricType: SERVER
    },
    {
      description: "Outgoing Connections",
      metricSuffix: "connections",
      metricType: CLIENT
    },
  ];


  /*
   * Maps metric definitions of a specific type to metric ids
   * using provided prefixes and definition suffixes
   */
  function toMetricIds(objs, metricType) {
    var metrics = _.filter(metricDefinitions, ["metricType", metricType]);
    return _(objs).map(function(obj) {
      return _.map(metrics, function(metric) {
        return obj.prefix + metric.metricSuffix;
      });
    }).flatten().value();
  }

  function serversToMetricIds(routers) {
    return toMetricIds(routers.servers(), SERVER);
  }

  function clientsToMetricIds(routers) {
    return toMetricIds(routers.clients(), CLIENT);
  }

  function desiredMetrics(routers) {
    return (serversToMetricIds(routers)).concat(clientsToMetricIds(routers));
  }

  function render($root, template, metricData) {
    $root.html(template({
      metrics : metricData
    }));
  }

  function filterMetricsByIds(metrics, ids) {
    return _.filter(metrics, function(m) {
      return ids.indexOf(m.name) >= 0;
    });
  }

  /*
   * Filter metrics by suffix and return the sum of their deltas
   */
  function sumMetricsForDefinition(metrics, defn) {
    var filteredResponses = _.filter(metrics, function(m){ return m.name.indexOf(defn.metricSuffix) > 0 });
    return _.sumBy(filteredResponses, 'delta');
  }

  return function($root, template, routers) {
    render($root, template, metricDefinitions);
    return {
      onMetricsUpdate: function(data) {
        var serverMetrics = filterMetricsByIds(data.specific, serversToMetricIds(routers));
        var clientMetrics = filterMetricsByIds(data.specific, clientsToMetricIds(routers));

        var transformedData = _.map(metricDefinitions, function(defn) {
          var metrics = defn.metricType === SERVER ? serverMetrics : clientMetrics;
          var value = sumMetricsForDefinition(metrics, defn);
          return {
            description: defn.description,
            value: value
          }
        });

        render($root, template, transformedData);

      },
      desiredMetrics: function() { return desiredMetrics(routers); }
    };
  }
})();
