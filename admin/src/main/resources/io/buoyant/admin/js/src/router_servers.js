"use strict";

define([
  'jQuery',
  'lodash',
  'Handlebars',
  'src/router_server',
  'template/compiled_templates'
], function($, _, Handlebars, RouterServer, templates) {
    var serverContainerTemplate = templates.router_server_container;
    var rateMetricPartial = templates["server_rate_metric.partial"];

    var RouterServers = (function() {
    return function (metricsCollector, routers, $serverEl, routerName) {
      var servers = routers.servers(routerName);
      Handlebars.registerPartial('rateMetricPartial', rateMetricPartial);

      _.map(servers, function(server) {
        var $el = $(serverContainerTemplate());
        $serverEl.append($el);
        RouterServer(metricsCollector, server, $el, routerName);
      });
    }
  })();
  return RouterServers;
});
