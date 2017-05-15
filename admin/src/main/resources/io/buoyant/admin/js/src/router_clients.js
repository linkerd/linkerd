"use strict";

define([
  'jQuery',
  'src/colors',
  'src/router_client',
  'src/combined_client_graph',
  'template/compiled_templates'
], function($,
  Colors,
  RouterClient,
  CombinedClientGraph,
  templates
) {
  var RouterClients = (function() {
    var EXPAND_CLIENT_THRESHOLD = 6;
    var TRANSFORMER_RE = /(\/%\/[^\$#]*)?(\/[\$#]\/.*)/;

    function assignColorsToClients(colors, clients) {
      return _.reduce(clients, function(clientMapping, client, idx) {
        clientMapping[client] = colors[idx % colors.length];
        return clientMapping;
      }, {});
    }

    function shouldExpandClient(numClients) {
      // if there are many clients, collapse them by default to improve page perfomance
      return numClients < EXPAND_CLIENT_THRESHOLD;
    }

    return function (metricsCollector, initialData, $clientEl, $combinedClientGraphEl, routerName) {
      var clientContainerTemplate = templates.router_client_container;

      var clients = _.sortBy(initialData[routerName].clients);
      var colorList = Colors;
      var clientToColor = assignColorsToClients(colorList, clients);
      var combinedClientGraph = CombinedClientGraph(metricsCollector, initialData, routerName, $combinedClientGraphEl, clientToColor);

      var routerClients = _.map(clients, function(client) {
        return initializeClient(client);
      });

      routerClients = _.compact(routerClients);

      if (routerClients.length == 0) {
        $clientEl.hide();
      }

      metricsCollector.onAddedClients(addClients);

      function initializeClient(client) {
        $clientEl.show();
        var colorsForClient = clientToColor[client];
        var match = ('/' + client).match(TRANSFORMER_RE);

        //client names that don't conform to the regex are not displayed
        if (!match) return null;

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

        var shouldExpand = shouldExpandClient(initialData[routerName].clients.length);
        return RouterClient(metricsCollector, client, $container, routerName, colorsForClient, shouldExpand, combinedClientGraph);
      }

      function addClients(addedClients) {
        // filter new clients
        var filteredClients = _.keys(addedClients[routerName]);

        // combine with existing clients
        clients = _(clients).concat(filteredClients).uniq().value();

        // reassign colors
        clientToColor = assignColorsToClients(colorList, clients);

        // pass new colors to combined request graph, add new clients to graph
        combinedClientGraph.updateColors(clientToColor);
        combinedClientGraph.addClients(filteredClients);

        // add new clients to dom
        _.each(filteredClients, function(clientForRouter) {
          var routerClient = initializeClient(clientForRouter);
          if (routerClient) {
            routerClients.push(routerClient);
          }
        });
      }
    }
  })();

  return RouterClients;
});
