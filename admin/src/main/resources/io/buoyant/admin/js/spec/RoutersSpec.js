define([
  'jQuery',
  'lodash',
  'spec/fixtures/metrics',
  'src/routers'
], function($, _, metricsJson, Routers) {
  describe("Routers", function() {
    var fakeMetricsCollector = function() {};
    var routers = Routers(metricsJson, fakeMetricsCollector);

    describe("clients", function() {
      it("provides the clients associated with a specified router", function() {
        var multiplierClients = routers.clients("multiplier");
        var expectedLabels = ["$/inet/127.1/9092", "$/inet/127.1/9030", "$/inet/127.1/9029"];

        expect(multiplierClients.length).toBe(3);
        expect(_.map(multiplierClients, 'label')).toEqual(expectedLabels);

        _.each(multiplierClients, function(client) {
          expect(client.router).toBe("multiplier");
          expect(client.color).toBeTruthy(); // nonempty string
          expect(client.prefix.indexOf("rt/multiplier/dst/id")).toBe(0);
        });
      });

      it("returns all clients if router is invalid or unspecified", function() {
        expect(routers.clients("foobar").length).toEqual(10);
        expect(routers.clients().length).toEqual(10);
      });
    });

    describe("servers", function() {
      it("returns the servers associated with a specified router", function() {
        var dividerServers = routers.servers("divider");
        var server = dividerServers[0];

        expect(dividerServers.length).toBe(1);
        expect(server.ip).toBe("0.0.0.0");
        expect(server.port).toBe("4117");
        expect(server.router).toBe("divider");
      });

      it("returns all servers if router is invalid or unspecified", function() {
        expect(routers.servers("foobar").length).toBe(4);
        expect(routers.servers(null).length).toBe(4);
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
