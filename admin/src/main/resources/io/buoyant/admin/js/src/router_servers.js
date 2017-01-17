"use strict";

define([
  'jQuery',
  'lodash',
  'Handlebars',
  'src/router_server',
  'text!template/router_server_container.template',
  'text!template/server_rate_metric.partial.template'
], function($, _, Handlebars, RouterServer, routerServerContainerTemplate, serverMetricPartialTemplate) {
    var serverContainerTemplate = Handlebars.compile(routerServerContainerTemplate);
    var rateMetricPartial = Handlebars.compile(serverMetricPartialTemplate);

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
