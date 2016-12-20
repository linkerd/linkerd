define([
  'jQuery',
  'lodash',
  'Handlebars',
  'app/router_server'
], function($, _, Handlebars, RouterServer) {
    var RouterServers = (function() {
    return function (metricsCollector, routers, $serverEl, routerName, serverTemplate, rateMetricPartial, serverContainerTemplate) {
      var servers = routers.servers(routerName);
      Handlebars.registerPartial('rateMetricPartial', rateMetricPartial);

      _.map(servers, function(server) {
        var $el = $(serverContainerTemplate());
        $serverEl.append($el);
        RouterServer(metricsCollector, server, $el, routerName, serverTemplate);
      });
    }
  })();
  return RouterServers;
});