"use strict";

define([
  'src/query', //TODO: remove
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
          strokeStyle: clientColors[name.split("/requests")[0]].color,
          lineWidth: 2
        };
      };
    }

    function getClientsToQuery(routers, routerName) {
      var clients = routers.clients(routerName);
      var nonIgnoredClients = _.difference(clients, ignoredClients[routerName]);

      // if all clients are collapsed, let the combined graph show all clients
      return _.isEmpty(nonIgnoredClients) ? clients : nonIgnoredClients;
    }

    return function(metricsCollector, routers, routerName, $root, colors) {
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

      var clientsToQuery = getClientsToQuery(routers, routerName);
      var desiredMetrics = _.map(clientsToQuery, function(client) {
        return { name: client.label + "/requests" };
      });
      chart.setMetrics(desiredMetrics);

      var metricsListener = function(data) {
        var clientData = _.get(data.treeSpecific, ["rt", routerName, "dst", "id"]);
        var dataToDisplay = _.map(clientData, function(d, name) {
          return {
            name: name + "/requests",
            delta: _.get(d, "requests.delta")
          };
        });
        chart.updateMetrics(dataToDisplay);
      };

      metricsCollector.registerListener(metricsListener, function() {
        var clientsToQuery = getClientsToQuery(routers, routerName);
        var metrics = _.map(clientsToQuery, function(client) {
          return ["rt", routerName, "dst", "id", client.label, "requests", "counter"];
        });
        return metrics;
      });
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
