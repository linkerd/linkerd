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

    return function (metricsCollector, routers, $clientEl, routerName, colors) {
      var clientContainerTemplate = Handlebars.compile(routerClientContainerTemplate);

      var clients = routers.clients(routerName);
      var colorList = colors;
      var clientToColor = assignColorsToClients(colorList, clients);
      var combinedClientGraph = CombinedClientGraph(metricsCollector, routers, routerName, $clientEl.find(".router-graph"), clientToColor);

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
        var $clientId = $container.find(".client-id");
        var $transformerPrefix = $container.find(".transformer-prefix")
        $clientId.click(function() {
            $transformerPrefix.toggle("slow", function() {});
        });

        var $metrics = $container.find(".metrics-container");
        var $chart = $container.find(".chart-container");
        var $toggle = $container.find(".client-toggle");

        return RouterClient(metricsCollector, routers, client, $metrics, routerName, $chart, colorsForClient, $toggle, expandClients);
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
