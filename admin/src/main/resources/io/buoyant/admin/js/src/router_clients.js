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
      var clientMapping = {};

      _(clients).sortBy('label').forEach(function(client, idx) {
        clientMapping[client.label] = colors[idx % colors.length];
      });

      return clientMapping;
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
        return initializeClient(client);
      });

      if (routerClients.length == 0) {
        $clientEl.hide();
      }

      routers.onAddedClients(addClients);

      function initializeClient(client) {
        $clientEl.show();
        var colorsForClient = clientToColor[client.label];
        var $container = $(clientContainerTemplate({
          clientColor: colorsForClient.color,
          client: client.label
        }));
        var inserted = false;
        $clientEl.find(".client-container").each(function() {
          if (client.label < $(this).data('label')) {
            $container.insertBefore($(this));
            inserted = true;
            return false;
          }
        })
        if (!inserted) {
          $container.appendTo($clientEl);
        }

        return RouterClient(metricsCollector, routers, client, $container, routerName, colorsForClient, expandClients);
      }

      function addClients(addedClients) {
        // reassign colors
        clientToColor = assignColorsToClients(colors, routers.clients(routerName));

        // update existing client colors
        combinedClientGraph.updateColors(clientToColor);

        _.each(routerClients, function(routerClient) {
          routerClient.updateColors(clientToColor);
        });

        expandClients = shouldExpandClients(routers.clients(routerName).length);

        // add new clients
        _(addedClients)
          .filter(function(client) { return client.router === routerName })
          .each(function(clientForRouter) {
            routerClients.push(initializeClient(clientForRouter));
            combinedClientGraph.addClient(clientForRouter);
          });
      }
    }
  })();

  return RouterClients;
});
