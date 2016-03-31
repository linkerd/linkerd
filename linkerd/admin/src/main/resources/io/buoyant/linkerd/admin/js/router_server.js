var RouterServer = (function() {
  // Skeletal implementation to demonstrate functionality of RouterController

  function initializeServerContainers(servers, $serverEl) {
    _.each(servers, function(server) {
      var $se = $("<div />").addClass("router-server")
        .html("<div>Servers</div><div>" + server.prefix + "</div>");
      $se.appendTo($serverEl);
    });
  }

  function renderServers() {
  }

  return function (routers, $serverEl, routerName) {
    var servers = routers.servers(routerName);
    initializeServerContainers(servers, $serverEl);

    return {
      onMetricsUpdate: function() {
        renderServers();
      },
      desiredMetrics: function () {
        return [];
      }
    }
  };
})();
