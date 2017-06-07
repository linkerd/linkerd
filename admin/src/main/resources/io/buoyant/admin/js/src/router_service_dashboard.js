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
    var clientMetricsByService =  _.chain(_.get(data, ["rt", router, "client"]))
      .map(function(clientData, client) {
        return _.map(clientData.service, function(svcData, svc) {
          return { service: svc, client: client, requests: svcData.requests.delta };
        });
      })
      .flatten()
      .groupBy("service")
      .value();

   var serviceStats = _.reduce(_.get(data, ["rt", router, "service"]), function(mem, svcData, svc) {
    mem[svc] = {
      serviceStats: svcData,
      clientStats: clientMetricsByService[svc]
    };
    return mem;
   }, {});

   return serviceStats;
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
        initializeService($routerContainer, metricsCollector, router, svc, initialData);
      });
    });
  }

  function initializeService($routerContainer, metricsCollector, router, svc, initialData) {
    svcsByRouter[router][svc] = RouterService(metricsCollector, $routerContainer, router, svc, initialData);
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
      // see if there are any new services, and add them
      var initialServiceData = {};

      _.each(addedClients, function(clients, router) {
        initialServiceData[router] = {};

        var $routerContainer = $($container.find("[data-router='" + router + "']")[0]);
        initialServiceData[router]["clients"] = _.keys(clients);

        _.each(clients, function(_d, client) {
          var services = _.keys(_.get(metricsJson, ["rt", router, "client", client, "service"]));

          _.each(services, function(svc) {
            if(!svcsByRouter[router][svc]) {
              initializeService($routerContainer, metricsCollector, router, svc, initialServiceData);
            } else {
              svcsByRouter[router][svc].onAddedClients(clients);
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
