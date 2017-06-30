"use strict";

define([
  'jQuery',
  'lodash',
  'src/router_service_dashboard',
  'src/metrics_collector',
  'spec/fixtures/metrics'
], function($, _, RouterServices, MetricsCollector, metricsJson) {
  describe("RouterServices", function() {
    var $container;

    var expiredMetricsJson = {
      rt: {
        multiplier: {
          client: {
            "$/inet/127.1/9092": {},
            "$/inet/127.1/9030": {},
            "$/inet/127.1/9029": {}
          }
        }
      }
    }

    beforeEach(function () {
      $container = $("<div />");
    });

    afterEach(function () {
      $container.remove();
      $container = null;
    });

    it("initializes and renders the services", function() {
      var initialData = {
        adder: { services: ["svc"] },
        multiplier: { services: ["svc"]}
      }
      var realCollector = MetricsCollector(metricsJson);

      RouterServices(realCollector, initialData, $container);
      realCollector.__update__(metricsJson);

      var $multiplierRouter = $($container.find("[data-router='multiplier']")[0]);
      var $multiplierSvcs = $multiplierRouter.find(".svc-container");
      var $svcClients = $($multiplierSvcs[0]).find(".client-metrics");

      expect($multiplierSvcs.length).toBe(1);
      expect($svcClients.length).toBe(3);
    });

    it("doesn't show expired clients", function() {
      var initialData = {
        multiplier: { services: ["svc"] }
      }
      var realCollector = MetricsCollector(metricsJson);

      RouterServices(realCollector, initialData, $container);
      realCollector.__update__(metricsJson);

      var $multiplierRouter = $($container.find("[data-router='multiplier']")[0]);
      var $multiplierSvcs = $multiplierRouter.find(".svc-container");
      var $svcClients = $($multiplierSvcs[0]).find(".client-metrics");

      expect($multiplierSvcs.length).toBe(1);
      expect($svcClients.length).toBe(3);

      realCollector.__update__(expiredMetricsJson);
      $svcClients = $($multiplierSvcs[0]).find(".client-metrics");

      expect($svcClients.length).toBe(0);
    });

    it("displays the clients by service", function() {
      var initialData = {
        service_test: { services: ["service_1", "service_2"]}
      }
      var realCollector = MetricsCollector(metricsJson);

      RouterServices(realCollector, initialData, $container);
      realCollector.__update__(metricsJson);

      var $testRouter = $($container.find("[data-router='service_test']")[0]);
      var $multipleSvcs = $testRouter.find(".svc-container");
      var $svcClients1 = $($multipleSvcs[0]).find(".client-metrics");
      var $svcClients2 = $($multipleSvcs[1]).find(".client-metrics");

      expect($multipleSvcs.length).toBe(2);
      expect($svcClients1.length).toBe(5);
      expect($svcClients2.length).toBe(3);
    });
  });
});
