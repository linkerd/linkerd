var RouterClients = (function() {
  function assignColorsToClients(colors, clients) {
    var colorIdx = 0;

    return _.reduce(clients, function(clientMapping, client) {
      clientMapping[client.label] = colors[colorIdx++ % colors.length];
      return clientMapping;
    }, {});
  }

  return function (metricsCollector, routers, $clientEl, routerName, clientTemplate, clientContainerTemplate, colors) {
    var clientToColor = assignColorsToClients(colors, routers.clients(routerName));

    var routerClients = [];
    var combinedClientGraph = CombinedClientGraph(metricsCollector, routerName, $clientEl.find(".router-graph"), clientToColor);

    routers.onAddedClients(addClients);

    _.map(routers.clients(routerName), initializeClient);

    function initializeClient(client) {
      var colorsForClient = clientToColor[client.label];
      var $container = $(clientContainerTemplate({
        clientColor: colorsForClient.color
      })).appendTo($clientEl);
      var $metrics = $container.find(".metrics-container");
      var $chart = $container.find(".chart-container");

      routerClients.push(RouterClient(metricsCollector, routers, client, $metrics, routerName, clientTemplate, $chart, colorsForClient));
    }

    function addClients(addedClients) {
      // reassign colors
      clientToColor = assignColorsToClients(colors, routers.clients(routerName));

      // update existing client colors
      combinedClientGraph.updateColors(clientToColor);

      _.each(routerClients, function(routerClient) {
        routerClient.updateColors(clientToColor);
      });

      // add new clients
      _.chain(addedClients)
        .filter(function(client) { return client.router === routerName })
        .each(initializeClient)
        .value();
    }
  }
})();
