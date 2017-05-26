"use strict";

define([
  'jQuery',
  'lodash',
  'src/router_clients',
  'src/metrics_collector',
  'spec/fixtures/metrics',
  'template/compiled_templates'
], function($, _, RouterClients, MetricsCollector, metricsJson, templates) {
  describe("RouterClients", function() {
    var collector;
    var $container;
    var $clientsEl;
    var $combinedClientGraphEl;
    var StubMetricsCollector = function() {
      return {
        start: _.noop,
        registerListener: _.noop,
        deregisterListener: _.noop,
        onAddedClients: _.noop
      };
    }

    var initialRouterData = {
      adder: {
        clients: [
          "$/inet/127.1/9091",
          "$/inet/127.1/9090",
          "$/inet/127.1/9093",
          "$/inet/127.1/9080",
          "$/inet/127.1/9085"
        ]
      },
      divider: {
        clients: [
          "$/inet/127.1/9031",
          "$/inet/127.1/9032",
          "$/inet/127.1/9033",
          "$/inet/127.1/9034",
          "$/inet/127.1/9035",
          "$/inet/127.1/9036"
        ]
      },
      multiplier: {
        clients: [
          "$/inet/127.1/9030",
          "$/inet/127.1/9029",
          "/%/io.l5d.port/4141/#/io.l5d.fs/foo",
          "does-not-compute/",
          "does_not_obey_rules"
        ]
      },
      lots_of_clients: {
        clients: [
          "$/inet/127.1/1111",
          "$/inet/127.1/2222",
          "$/inet/127.1/3333",
          "$/inet/127.1/4444",
          "$/inet/127.1/5555",
          "$/inet/127.1/6666",
          "$/inet/127.1/7777"
        ]
      }
    }

    var expiredMetricsJson = {
      rt: {
        to_be_expired: {
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
      collector = StubMetricsCollector();
      var containers = templates.router_container({ routers: ["fake_router"] });
      $container.html(containers);

      $clientsEl = $($container.find(".clients")[0]);
      $combinedClientGraphEl = $($container.find(".router-graph")[0]);
    });

    afterEach(function () {
      $clientsEl.remove();
      $clientsEl = null;
      $combinedClientGraphEl.remove();
      $combinedClientGraphEl = null;
      $container.remove();
      $container = null;

      collector = null;
    });

    it("initializes and renders the clients", function() {
      var routerData = _.merge({
        few_clients: {
          clients: [
            "$/inet/127.1/1111",
            "$/inet/127.1/2222",
            "$/inet/127.1/3333",
            "$/inet/127.1/4444"
          ]
        }
      }, initialRouterData);

      RouterClients(collector, routerData, $clientsEl, $combinedClientGraphEl, "few_clients");
      var clientContainers = $clientsEl.find(".client-container");
      var contentContainers = $clientsEl.find(".client-content-container");

      expect(clientContainers.length).toBe(4);
      expect(contentContainers.length).toBe(4);

      _.each(contentContainers, function(clientContainer) {
        expect($(clientContainer).css("display")).toBe('');
      });
    });

    it("ignores clients with names we don't expect", function() {
      RouterClients(collector, initialRouterData, $clientsEl, $combinedClientGraphEl, "multiplier");
      var clientContainers = $clientsEl.find(".client-container");
      var contentContainers = $clientsEl.find(".client-content-container");

      expect(clientContainers.length).toBe(3);
      expect(contentContainers.length).toBe(3);

      _.each(contentContainers, function(clientContainer) {
        expect($(clientContainer).css("display")).toBe('');
      });
    });

    it("collapses clients when there are 6 or more of them", function() {
      RouterClients(collector, initialRouterData, $clientsEl, $combinedClientGraphEl, "divider");
      var contentContainers = $clientsEl.find(".client-content-container");

      expect(contentContainers.length).toBe(6);
      _.each(contentContainers, function(clientContainer) {
        expect($(clientContainer).css("display")).toBe("none");
      });
    });

    it("doesn't display a client section if there are zero clients", function() {
      var routerData = _.merge({
        nothing_here: { clients: [] }
      }, initialRouterData);

      RouterClients(collector, routerData, $clientsEl, $combinedClientGraphEl, "nothing_here");
      var clientContainers = $clientsEl.find(".client-container");

      expect(clientContainers.length).toBe(0);
    });

    it("stops rendering clients when they are expired", function() {
      var realCollector = MetricsCollector(metricsJson);
      var routerData = _.merge({
        to_be_expired: {
          clients: [
            "$/inet/127.1/9030",
            "$/inet/127.1/9029",
            "$/inet/127.1/9092"
          ]
        }
      }, initialRouterData);

      RouterClients(realCollector, routerData, $clientsEl, $combinedClientGraphEl, "to_be_expired");

      var clientContainers = $clientsEl.find(".client-container");
      var contentContainers = $clientsEl.find(".client-content-container");

      _.each(contentContainers, function(clientContainer) {
        expect($(clientContainer).css("display")).toBe('');
      });

      realCollector.__update__(expiredMetricsJson);

      expect(clientContainers.length).toBe(3);
      expect(contentContainers.length).toBe(3);

      _.each(contentContainers, function(clientContainer) {
        expect($(clientContainer).css("display")).toBe('none');
      });
    });

    it("adds and collapses new clients if are many clients already", function() {
      var realCollector = MetricsCollector(metricsJson);
      var addMoreClientsJson = _.merge({}, metricsJson, {
        rt: {
          lots_of_clients: {
            client: {
              "$/inet/127.1/8888": {
                "connect_latency_ms": {
                  "stat.count": 0
                }
              },
              "$/inet/127.1/9999": {
                "connect_latency_ms": {
                  "stat.count": 0
                }
              }
            }
          }
        }
      });

      RouterClients(realCollector, initialRouterData, $clientsEl, $combinedClientGraphEl, "lots_of_clients");

      var clientContainers = $clientsEl.find(".client-container");
      var contentContainers = $clientsEl.find(".client-content-container");

      expect(clientContainers.length).toBe(7);
      expect(contentContainers.length).toBe(7);
      _.each(contentContainers, function(clientContainer) {
        expect($(clientContainer).css("display")).toBe('none');
      });

      realCollector.__update__(addMoreClientsJson);

      clientContainers = $clientsEl.find(".client-container");
      contentContainers = $clientsEl.find(".client-content-container");
      expect(clientContainers.length).toBe(9);
      expect(contentContainers.length).toBe(9);

      _.each(contentContainers, function(clientContainer) {
        expect($(clientContainer).css("display")).toBe('none');
      });
    });
  });
});
