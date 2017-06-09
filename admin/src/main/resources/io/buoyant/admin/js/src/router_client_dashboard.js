"use strict";

define([
  'lodash',
  'src/router_summary',
  'src/router_servers',
  'src/router_clients',
  'template/compiled_templates'
], function(
  _,
  RouterSummary,
  RouterServers,
  RouterClients,
  templates
) {
  // Contains all the router components, e.g. summaries, graphs, etc

  function initializeRouterContainers(selectedRouter, initialData, $parentContainer) {
    var template = templates.router_container;
    var routersToShow = selectedRouter === "all" ? _.keys(initialData) : [selectedRouter];

    var routerLabels = [];
    $(".router-menu-option").each(function() {
      var label = $(this).text();
      if (routersToShow.indexOf(label) !== -1) {
        routerLabels.push(label);
      }
    });

    var containers = template({ routers: routerLabels });
    $parentContainer.html(containers);

    var routerContainers = {};
    $(".router").each(function (i,el) {
      var $el = $(el);
      routerContainers[$el.data("router")] = $el;
    });

    return routerContainers;
  }

  return function(metricsCollector, selectedRouter, initialData, $parentContainer, routerConfig) {
    var routerContainerEls = initializeRouterContainers(selectedRouter, initialData, $parentContainer);

    _.each(routerContainerEls, function(container, router) {
      var $summaryEl = $(container.find(".summary")[0]);
      var $serversEl = $(container.find(".servers")[0]);
      var $clientsEl = $(container.find(".clients")[0]);
      var $combinedClientGraphEl = $(container.find(".router-graph")[0]);
      var $routerStatsEl = $(container.find(".router-stats")[0]);

      RouterSummary(metricsCollector, $summaryEl, $routerStatsEl, router, routerConfig);
      RouterServers(metricsCollector, initialData, $serversEl, router);
      RouterClients(metricsCollector, initialData, $clientsEl, $combinedClientGraphEl, router);
    });

    return {};
  };
});
