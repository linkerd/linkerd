"use strict";

define([
  'jQuery',
  'src/router_client',
  'src/combined_client_graph',
  'template/compiled_templates'
], function($,
  RouterClient,
  CombinedClientGraph,
  templates
) {
  var RouterClients = (function() {
    var EXPAND_CLIENT_THRESHOLD = 6;
    var TRANSFORMER_RE = /(\/%\/[^\$#]*)?(\/[\$#]\/.*)/;

    function assignColorsToClients(colors, clients) {
      return _.reduce(clients, function(clientMapping, client, idx) {
        clientMapping[client.label] = colors[idx % colors.length];
        return clientMapping;
      }, {});
    }

    function shouldExpandClient(numClients) {
      // if there are many clients, collapse them by default to improve page perfomance
      return numClients < EXPAND_CLIENT_THRESHOLD;
    }

    return function (metricsCollector, routers, $clientEl, $combinedClientGraphEl, routerName, colors) {
      var clientContainerTemplate = templates.router_client_container;

      var clients = routers.clients(routerName);
      var colorList = colors;
      var clientToColor = assignColorsToClients(colorList, clients);
      var combinedClientGraph = CombinedClientGraph(metricsCollector, routers, routerName, $combinedClientGraphEl, clientToColor);

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
          var $transformerPrefix = $container.find(".transformer-prefix");
          var $clientSuffix = $container.find(".client-suffix");

          $container.find(".client-id")
            .click(function() {
              $transformerPrefix.toggle("slow", function() {
                $clientSuffix.toggleClass("is-first", !$transformerPrefix.is(":visible"));
              });
            });
        }

        var shouldExpand = shouldExpandClient(routers.clients(routerName).length);
        return RouterClient(metricsCollector, routers, client, $container, routerName, colorsForClient, shouldExpand, combinedClientGraph);
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
