"use strict";

define([
  'src/utils'
], function(Utils) {
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

    function getClientsToDisplay(routers, routerName) {
      var clients = routers.clients(routerName);

      var nonIgnoredClients = _(clients).map(function(client) {
        return !ignoredClients[routerName][client.label] ? client : null;
      }).compact().value();

      // if all clients are collapsed, let the combined graph show all clients
      return _.isEmpty(nonIgnoredClients) ? clients : nonIgnoredClients;
    }

    return function(metricsCollector, routers, routerName, $root, colors) {
      ignoredClients[routerName] = {};

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

      var desiredMetrics = _.map(routers.clients(routerName), function(client) {
        return { name: client.label + "/requests" };
      });
      chart.setMetrics(desiredMetrics);

      var metricsListener = function(data) {
        var clientData = _.get(data, ["rt", routerName, "dst", "id"]);
        var clientsToDisplay = getClientsToDisplay(routers, routerName);

        var dataToDisplay = _.map(clientsToDisplay, function(client) {
          return {
            name: client.label + "/requests",
            delta: _.get(clientData, [client.label, "requests", "delta"]) || 0
          };
        });

        chart.updateMetrics(dataToDisplay);
      };

      metricsCollector.registerListener(metricsListener);
      return {
        addClients: function(clients) {
          chart.addMetrics(_.map(clients, function(client) {
            return clientToMetric(client.label + "/requests");
          }));
        },

        ignoreClient: function(client) {
          ignoredClients[routerName][client.label] = true;
        },

        unIgnoreClient: function(client) {
          ignoredClients[routerName][client.label] = false;
        },

        updateColors: function(newColors) {
          chart.updateTsOpts(timeseriesParamsFn(newColors));
        }
      };
    };
  })();
  return CombinedClientGraph;
});
