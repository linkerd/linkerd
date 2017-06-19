"use strict";

define([
  'jQuery',
  'template/compiled_templates',
  'src/router_service',
  'bootstrap'
], function(
  $,
  templates,
  RouterService
) {
  var svcsByRouter = {};

  function extractSvcClientStats(data, router) {
    return _.chain(_.get(data, ["rt", router, "client"]))
      .map(function(clientData, client) {
        return _.map(clientData.service, function(svcData, svc) {
          return { service: svc, client: client, requests: svcData.requests.delta };
        });
      })
      .flatten()
      .groupBy("service")
      .value();
  }

  function initializeRouterServices(metricsCollector, initialData, $container) {
    _.each(initialData, function(routerData, router) {
      svcsByRouter[router] = svcsByRouter[router] || {};

      var $routerContainer = $(templates.router_services_container({
        router: router
      }));
      $container.append($routerContainer);

      if(_.isEmpty(routerData.services)) {
        $routerContainer.find(".svc-subsection").addClass("hidden");
      }

      _.each(routerData.services, function(svc) {
        initializeService($routerContainer, metricsCollector, router, svc);
      });
    });
  }

  function initializeService($routerContainer, metricsCollector, router, svc) {
    svcsByRouter[router][svc] = RouterService(metricsCollector, $routerContainer, svc);
  }

  return function(metricsCollector, initialData, $container) {
    initializeRouterServices(metricsCollector, initialData, $container);
    metricsCollector.registerListener("RouterServices", metricsHandler);
    metricsCollector.onAddedClients(addServices);

    function metricsHandler(metrics) {
      // because we need to figure out which clients are associated with which
      // services, handle metrics in RouterServices, rather than repeating
      // this work within each RouterService
      _.each(svcsByRouter, function(services, router) {
        var svcClientMetrics = extractSvcClientStats(metrics, router);

        _.each(services, function(svc, svcName) {
          svc.onMetricsUpdate(svcClientMetrics[svcName]);
        });
      });
    }

    function addServices(addedClients, metricsJson) {
      _.each(addedClients, function(clients, router) {
        var $routerContainer = $($container.find("[data-router='" + router + "']")[0]);

        _.each(clients, function(_d, client) {
          var services = _.keys(_.get(metricsJson, ["rt", router, "client", client, "service"]));

          _.each(services, function(svc) {
            if(!svcsByRouter[router][svc]) {
              initializeService($routerContainer, metricsCollector, router, svc);
            }
          });
        });

        if (!_.isEmpty(svcsByRouter[router])) {
          $routerContainer.find(".svc-subsection").removeClass("hidden");
        }
      });
    }
  };
});
