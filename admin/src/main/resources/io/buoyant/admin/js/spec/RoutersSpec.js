"use strict";

define([
  'jQuery',
  'lodash',
  'spec/fixtures/metrics',
  'src/routers'
], function($, _, metricsJson, Routers) {
  describe("Routers", function() {
    beforeEach(function () {
      jasmine.addMatchers({
        toHaveLength: function () {
          return {
            compare: function (actual, length) {
              return { pass: actual.length === length }
            }
          }
        }
      });
    });

    var fakeMetricsCollector = function() {};
    var routers = Routers(metricsJson, fakeMetricsCollector);

    describe("clients", function() {
      it("provides the clients associated with a specified router", function() {
        var multiplierClients = routers.clients("multiplier");
        var expectedLabels = ["$/inet/127.1/9029", "$/inet/127.1/9030", "$/inet/127.1/9092"];

        expect(multiplierClients).toHaveLength(3);
        expect(_.map(multiplierClients, 'label')).toEqual(expectedLabels);

        _.each(multiplierClients, function(client) {
          expect(client.router).toBe("multiplier");
          expect(client.color).not.toBe("");
          expect(client.prefix.indexOf("rt/multiplier/dst/id")).toBe(0);
        });
      });

      it("returns all clients if router is invalid or unspecified", function() {
        expect(routers.clients("foobar")).toHaveLength(10);
        expect(routers.clients()).toHaveLength(10);
      });

      it("sorts clients by label alphabetically", function() {
        var expectedLabels = [
          '$/inet/127.1/9029',
          '$/inet/127.1/9030',
          '$/inet/127.1/9070',
          '$/inet/127.1/9080',
          '$/inet/127.1/9085',
          '$/inet/127.1/9089',
          '$/inet/127.1/9090',
          '$/inet/127.1/9091',
          '$/inet/127.1/9092',
          '$/inet/127.1/9093'
        ];
        expect(_.map(routers.clients(), 'label')).toEqual(expectedLabels);
      });
    });

    describe("servers", function() {
      it("returns the servers associated with a specified router", function() {
        var dividerServers = routers.servers("divider");
        var server = dividerServers[0];

        expect(dividerServers).toHaveLength(1);
        expect(server.ip).toBe("0.0.0.0");
        expect(server.port).toBe("4117");
        expect(server.router).toBe("divider");
      });

      it("returns all servers if router is invalid or unspecified", function() {
        expect(routers.servers("foobar")).toHaveLength(4);
        expect(routers.servers(null)).toHaveLength(4);
      });

      it("sorts servers by label alphabetically", function() {
        var expectedLabels = [
          '0.0.0.0/4114',
          '0.0.0.0/4115',
          '0.0.0.0/4116',
          '0.0.0.0/4117'
        ];
        expect(_.map(routers.servers(), 'label')).toEqual(expectedLabels);
      });
    });

    describe("onAddedClients", function() {
      it("calls the callback when the addedClients event is triggered", function() {
        var dummy = jasmine.createSpy('addedClientsCallback');
        routers.onAddedClients(dummy);

        $("body").trigger("addedClients");

        expect(dummy).toHaveBeenCalled();
      });
    });
  });
});
