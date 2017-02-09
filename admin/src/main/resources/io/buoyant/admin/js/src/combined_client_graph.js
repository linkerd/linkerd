"use strict";

define([
  'src/query',
  'src/utils'
], function(Query, Utils) {
  var CombinedClientGraph = (function() {
    var ignoredClients = {};

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

    function getClientsToQuery(routers, routerName) {
      return _.difference(routers.clients(routerName), ignoredClients[routerName]);
    }

    function getQuery(routerName, clientsToQuery) {
      var clients = _.map(clientsToQuery, 'label');
      return Query.clientQuery().withRouter(routerName).withClients(clients).withMetric("requests").build();
    }

    return function(metricsCollector, routers, routerName, $container, colors) {
      var $root = $container.find(".router-graph");
      var $noContentWarning = $container.find(".all-collapsed-warning").hide();
      ignoredClients[routerName] = [];

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

      var query = getQuery(routerName, getClientsToQuery(routers, routerName));
      var desiredMetrics = _.map(Query.filter(query, metricsCollector.getCurrentMetrics()), clientToMetric);
      chart.setMetrics(desiredMetrics);

      var metricsListener = function(data) {
        var clientsToQuery = getClientsToQuery(routers, routerName);
        var dataToDisplay = [];

        if(_.isEmpty(clientsToQuery)) {
          _.isEmpty(ignoredClients[routerName]) ? null : $noContentWarning.show("slow");
        } else {
          var metricQuery = getQuery(routerName, clientsToQuery);
          dataToDisplay = Query.filter(metricQuery, data.specific);
          $noContentWarning.hide("slow");
        }

        chart.updateMetrics(dataToDisplay);
      };

      metricsCollector.registerListener(metricsListener, function(metrics) { return Query.filter(query, metrics); });
      return {
        addClients: function(clients) {
          chart.addMetrics(_.map(clients, function(client) {
            return clientToMetric(client.prefix + "requests");
          }));
        },

        ignoreClient: function(client) {
          ignoredClients[routerName].push(client);
        },

        unIgnoreClient: function(client) {
          _.remove(ignoredClients[routerName], client);
        },

        updateColors: function(newColors) {
          chart.updateTsOpts(timeseriesParamsFn(newColors));
        }
      };
    };
  })();
  return CombinedClientGraph;
});
