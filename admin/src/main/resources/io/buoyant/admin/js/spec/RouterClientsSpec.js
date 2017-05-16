"use strict";

define([
  'jQuery',
  'lodash',
  'src/router_clients',
  'template/compiled_templates'
], function($, _, RouterClients, templates) {
  describe("RouterClients", function() {
    var collector;
    var $container = $("<div />");
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
        clients: ["$/inet/127.1/9030", "$/inet/127.1/9029", "$/inet/127.1/9092"]
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
          "/%/io.l5d.port/4141/#/io.l5d.fs/foo",
          "does-not-compute/",
          "does_not_obey_rules"
        ]
      },
      nothing_here: { clients: [] }
    }

    beforeEach(function () {
      collector = StubMetricsCollector();
      var containers = templates.router_container({ routers: ["adder"] });
      $container.html(containers);

      $clientsEl = $($container.find(".clients")[0]);
      $combinedClientGraphEl = $($container.find(".router-graph")[0]);
    });

    it("initializes and renders the clients", function() {
      RouterClients(collector, initialRouterData, $clientsEl, $combinedClientGraphEl, "adder");
      var clientContainers = $clientsEl.find(".client-container");
      var contentContainers = $clientsEl.find(".client-content-container");

      expect(clientContainers.length).toBe(3);
      expect(contentContainers.length).toBe(3);

      _.each(contentContainers, function(clientContainer) {
        expect($(clientContainer).css("display")).toBe('');
      });
    });

    it("ignores clients with names we don't expect", function() {
      RouterClients(collector, initialRouterData, $clientsEl, $combinedClientGraphEl, "multiplier");
      var clientContainers = $clientsEl.find(".client-container");

      expect(clientContainers.length).toBe(2);
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
      RouterClients(collector, initialRouterData, $clientsEl, $combinedClientGraphEl, "nothing_here");
      var clientContainers = $clientsEl.find(".client-container");

      expect(clientContainers.length).toBe(0);
    });
  });
});
