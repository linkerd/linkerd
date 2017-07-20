"use strict";

define([
  'jQuery',
  'lodash',
  'handlebars.runtime',
  'src/router_server',
  'template/compiled_templates'
], function($, _, Handlebars, RouterServer, templates) {
    var serverContainerTemplate = templates.router_server_container;
    var rateMetricPartial = templates["rate_metric.partial"];

    var RouterServers = (function() {
    return function (metricsCollector, initialData, $serverEl, routerName) {
      var servers = initialData[routerName].servers;
      Handlebars.registerPartial('rateMetricPartial', rateMetricPartial);

      _.map(servers, function(server) {
        var $el = $(serverContainerTemplate({server: server}));
        $serverEl.append($el);
        RouterServer(metricsCollector, server, $el, routerName);
      });
    }
  })();
  return RouterServers;
});
