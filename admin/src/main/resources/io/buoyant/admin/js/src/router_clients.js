"use strict";

define([
  'jQuery', 'Handlebars',
  'src/router_client',
  'src/combined_client_graph',
  'text!template/router_client_container.template'
], function($, Handlebars,
  RouterClient,
  CombinedClientGraph,
  routerClientContainerTemplate) {
  var RouterClients = (function() {
    var EXPAND_CLIENT_THRESHOLD = 6;

    function assignColorsToClients(colors, clients) {
      var colorIdx = 0;

      return _.reduce(clients, function(clientMapping, client) {
        clientMapping[client.label] = colors[colorIdx++ % colors.length];
        return clientMapping;
      }, {});
    }

    function shouldExpandClients(numClients) {
      // if there are many clients, collapse them by default to improve page perfomance
      return numClients < EXPAND_CLIENT_THRESHOLD;
    }

    return function (metricsCollector, routers, $clientEl, routerName, colors) {
      var clientContainerTemplate = Handlebars.compile(routerClientContainerTemplate);

      var clientToColor = assignColorsToClients(colors, routers.clients(routerName));
      var combinedClientGraph = CombinedClientGraph(metricsCollector, routers, routerName, $clientEl.find(".router-graph"), clientToColor);
      var clients = routers.clients(routerName);

      var expandClients = shouldExpandClients(clients.length);

      var routerClients = _.map(clients, function(client) {
        return initializeClient(client, expandClients);
      });

      if (routerClients.length == 0) {
        $clientEl.hide();
      }

      routers.onAddedClients(addClients);

      function initializeClient(client, shouldExpand) {
        $clientEl.show();
        var colorsForClient = clientToColor[client.label];
        var $container = $(clientContainerTemplate({
          clientColor: colorsForClient.color,
          client: client.label
        })).appendTo($clientEl);
        var $metrics = $container.find(".metrics-container");
        var $chart = $container.find(".chart-container");
        var $toggle = $container.find(".client-toggle");

        return RouterClient(metricsCollector, routers, client, $metrics, routerName, $chart, colorsForClient, $toggle, shouldExpand);
      }

      function addClients(addedClients) {
        // reassign colors
        clientToColor = assignColorsToClients(colors, routers.clients(routerName));

        // update existing client colors
        combinedClientGraph.updateColors(clientToColor);

        _.each(routerClients, function(routerClient) {
          routerClient.updateColors(clientToColor);
        });

        var expandClients = shouldExpandClients(addedClients.length + routerClients.length);

        // add new clients
        _.chain(addedClients)
          .filter(function(client) { return client.router === routerName })
          .each(function(clientForRouter) {
            routerClients.push(initializeClient(clientForRouter, expandClients));
          })
          .value();
      }
    }
  })();

  return RouterClients;
});
