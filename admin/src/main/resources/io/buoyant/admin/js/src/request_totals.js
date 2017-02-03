"use strict";

define([
  'jQuery',
  'src/query',
  'template/compiled_templates'
  ], function($, Query, templates) {

  var RequestTotals = (function() {
    var template = templates.request_totals;

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
      }
    ];

    function desiredMetrics(possibleMetrics) {
      var metaQuery = _.map(metricDefinitions, "query.source");
      return Query.filter(new RegExp(metaQuery.join("|")), possibleMetrics);
    }

    function render($root, metricData) {
      $root.html(template({
        metrics : metricData
      }));
    }

    return function(metricsCollector, selectedRouter, $root) {
      function onMetricsUpdate(data) {
        var transformedData = _.map(metricDefinitions, function(defn) {
          var metricsByQuery = Query.filter(defn.query, data.specific);
          var value = _.sumBy(metricsByQuery, 'delta');
          return {
            description: defn.description,
            value: value
          };
        });

        render($root, transformedData);
      }

      if (!selectedRouter) {
        render($root, metricDefinitions);
        metricsCollector.registerListener(onMetricsUpdate, desiredMetrics);
      } else {
        $root.hide();
      }
      return {};
    }
  })();

  return RequestTotals;
});
