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
    var TRANSFORMER_RE = /(\/%\/[^\$#]*)?(\/[\$#]\/.*)/;

    function assignColorsToClients(colors, clients) {
      return _.reduce(clients, function(clientMapping, client, idx) {
        clientMapping[client.label] = colors[idx % colors.length];
        return clientMapping;
      }, {});
    }

    function shouldExpandClients(numClients) {
      // if there are many clients, collapse them by default to improve page perfomance
      return numClients < EXPAND_CLIENT_THRESHOLD;
    }

    return function (metricsCollector, routers, $clientEl, $combinedClientGraphEl, routerName, colors) {
      var clientContainerTemplate = Handlebars.compile(routerClientContainerTemplate);

      var clients = routers.clients(routerName);
      var colorList = colors;
      var clientToColor = assignColorsToClients(colorList, clients);
      var combinedClientGraph = CombinedClientGraph(metricsCollector, routers, routerName, $combinedClientGraphEl, clientToColor);

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
        var match = ('/' + client.label).match(TRANSFORMER_RE);
        var $container = $(clientContainerTemplate({
          clientColor: colorsForClient.color,
          prefix: match[1],
          client: match[2]
        })).appendTo($clientEl);
        if (match[1]) {
          var $clientId = $container.find(".client-id");
          var $transformerPrefix = $container.find(".transformer-prefix")
          $clientId.css("cursor", "pointer");
          $clientId.click(function() {
            $transformerPrefix.toggle("slow", function() {});
          });
        }

        return RouterClient(metricsCollector, routers, client, $container, routerName, colorsForClient, expandClients);
      }

      function addClients(addedClients) {
        // filter new clients
        var filteredClients = _.filter(addedClients, function(client) {
          return client.router === routerName;
        });

        // combine with existing clients
        var combinedClients = routerClients.concat(filteredClients);

        // reassign colors
        clientToColor = assignColorsToClients(colorList, combinedClients);

        // pass new colors to combined request graph, add new clients to graph
        combinedClientGraph.updateColors(clientToColor);
        combinedClientGraph.addClients(filteredClients);

        // add new clients to dom
        _.each(filteredClients, function(clientForRouter) {
          routerClients.push(initializeClient(clientForRouter));
        });
      }
    }
  })();

  return RouterClients;
});
