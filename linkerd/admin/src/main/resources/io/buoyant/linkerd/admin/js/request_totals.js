"use strict";

var RequestTotals = (function() {
  var metricDefinitions = [
    {
      description: "Current requests",
      query: Query.serverQuery().allRouters().allServers().withMetric("requests").build()
    },
    {
      description: "Pending",
      query: Query.serverQuery().allRouters().allServers().withMetric("load").build()
    },
    {
      description: "Incoming Connections",
      query: Query.serverQuery().allRouters().allServers().withMetric("connections").build()
    },
    {
      description: "Outgoing Connections",
      query: Query.clientQuery().allRouters().allClients().withMetric("connections").build()
    },
  ];

  function matchesQuery(metricName, defn) {
    return metricName.search(defn.query) >= 0;
  }

  function desiredMetrics(possibleMetrics) {
    return _.filter(possibleMetrics, function(m) {
      return _.find(metricDefinitions, function(defn) {
        return matchesQuery(m, defn);
      });
    })
  }

  function render($root, template, metricData) {
    $root.html(template({
      metrics : metricData
    }));
  }

  return function($root, template, initialList) {
    var metricsList = initialList;
    render($root, template, metricDefinitions);
    return {
      onMetricsUpdate: function(data) {
        metricsList = _.keys(data.general);
        var transformedData = _.map(metricDefinitions, function(defn) {
          var metricsByQuery = _.filter(data.specific, function(m) { return matchesQuery(m.name, defn); });
          var value = _.sumBy(metricsByQuery, 'delta');
          return {
            description: defn.description,
            value: value
          };
        });

        render($root, template, transformedData);

      },
      desiredMetrics: function() { return desiredMetrics(metricsList); }
    };
  }
})();
