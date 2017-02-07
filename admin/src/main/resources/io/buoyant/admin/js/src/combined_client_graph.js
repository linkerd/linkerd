"use strict";

define([
  'src/query',
  'src/utils'
], function(Query, Utils) {
  var CombinedClientGraph = (function() {
    function clientToMetric(client) {
      return { name: client }; //TODO: move to clientName only after v2 migration
    }

    function timeseriesParamsFn(clientColors) {
      return function(name) {
        return {
          strokeStyle: clientColors[name.match(Query.clientQuery().build())[2]].color,
          lineWidth: 2
        };
      };
    }

    return function(metricsCollector, routers, routerName, $root, colors) {
      var chart = new Utils.UpdateableChart(
        {
          minValue: 0,
          grid: {
            strokeStyle: '#878787',
            verticalSections: 1,
            millisPerLine: 10000,
            borderVisible: false
          },
          labels: {
            fillStyle: '#878787',
            fontSize: 12,
            precision: 0
          },
          millisPerPixel: 60
        },
        $root[0],
        function() {
          return $(".router").first().width();  // get this to display nicely on various screen widths
        },
        timeseriesParamsFn(colors)
      );

      var clients = _.map(routers.clients(routerName), 'label');

      var query = Query.clientQuery().withRouter(routerName).withClients(clients).withMetric("requests").build();
      var desiredMetrics = _.map(Query.filter(query, metricsCollector.getCurrentMetrics()), clientToMetric);
      chart.setMetrics(desiredMetrics);

      var metricsListener = function(data) {
        chart.updateMetrics(data.specific);
      };

      metricsCollector.registerListener(metricsListener, function(metrics) { return Query.filter(query, metrics); });
      return {
        addClients: function(clients) {
          chart.addMetrics(_.map(clients, function(client) {
            return clientToMetric(client.prefix + "requests");
          }));
        },

        updateColors: function(newColors) {
          chart.updateTsOpts(timeseriesParamsFn(newColors));
        }
      };
    };
  })();
  return CombinedClientGraph;
});
