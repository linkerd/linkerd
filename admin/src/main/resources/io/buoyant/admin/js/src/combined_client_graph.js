"use strict";

define([
  'src/utils'
], function(Utils) {
  /*
    A graph of client requests.
    Can register itself with the metrics collector for updates, or can be
    updated manually by calling "updateMetrics". Passing in true for
    providesOwnMetrics will prevent registration with the metrics collector.
  */
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

    function getClientsToDisplay(clients, routerName) {
      var nonIgnoredClients = [];
      var nonExpiredClients = [];

      _(clients).each(function(clientData, client) {
        // expired clients have empty data
        if (!_.isEmpty(clientData)) {
          nonExpiredClients.push(client);
          if (!ignoredClients[routerName][client]) {
            nonIgnoredClients.push(client);
          }
        }
      });

      return _.isEmpty(nonIgnoredClients) ? nonExpiredClients : nonIgnoredClients;
    }

    return function(metricsCollector, initialData, routerName, $root, colors, providesOwnMetrics) {
      ignoredClients[routerName] = {}; // clients that are minimized in the UI

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

      var desiredMetrics = _.map(initialData[routerName].clients, function(client) {
        return { name: client + "/requests" };
      });

      chart.setMetrics(desiredMetrics);

      if (!providesOwnMetrics) {
        metricsCollector.registerListener("CombinedClientGraph_" + routerName, metricsListener);
      }

      function metricsListener(data) {
        var clientData = _.get(data, ["rt", routerName, "client"]);
        var clientsToDisplay = getClientsToDisplay(clientData, routerName);

        var dataToDisplay = _.map(clientsToDisplay, function(client) {
          return {
            name: client + "/requests",
            delta: _.get(clientData, [client, "requests", "delta"]) || 0
          };
        });

        chart.updateMetrics(dataToDisplay);
      }

      return {
        addClients: function(clients) {
          chart.addMetrics(_.map(clients, function(client) {
            return clientToMetric(client + "/requests");
          }));
        },

        ignoreClient: function(client) {
          ignoredClients[routerName][client] = true;
        },

        unIgnoreClient: function(client) {
          ignoredClients[routerName][client] = false;
        },

        updateColors: function(newColors) {
          chart.updateTsOpts(timeseriesParamsFn(newColors));
        },

        updateMetrics: function(data) {
          chart.updateMetrics(data);
        }
      };
    };
  })();
  return CombinedClientGraph;
});
