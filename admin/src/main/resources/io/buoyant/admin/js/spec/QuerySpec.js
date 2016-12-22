define(['src/query'], function(Query) {
  describe("Query", function() {
    var routerName = "fooRouter";

    describe("generalQuery", function() { // we don't expose generalQuery, so test via clientQuery
      it("builds a query for a router and a metric", function() {
        var query = Query.clientQuery().withRouter(routerName).withMetric("fooMetric").build();
        var expectedRegex = new RegExp('^rt\/(fooRouter)\/dst\/id\/(.*)\/(fooMetric)$');

        expect(query).toEqual(expectedRegex);
      });

      it("builds a query for a router and all metrics", function() {
        var query = Query.clientQuery().withRouter(routerName).allMetrics().build();
        var expectedRegex = new RegExp('^rt\/(fooRouter)\/dst\/id\/(.*)\/(.*)$');

        expect(query).toEqual(expectedRegex);
      });

      it("builds a query for a router and several metrics", function() {
        var query = Query.clientQuery().withRouter(routerName).withMetrics(["foo1", "foo2", "foo3"]).build();
        var expectedRegex = new RegExp('^rt\/(fooRouter)\/dst\/id\/(.*)\/(foo1|foo2|foo3)$');

        expect(query).toEqual(expectedRegex);
      });

      it("builds a query for several routers and several metrics", function() {
        var query = Query.clientQuery().withRouters(["r1", "r2", "r2d2"]).withMetrics(["foo1", "foo2", "foo3"]).build();
        var expectedRegex = new RegExp('^rt\/(r1|r2|r2d2)\/dst\/id\/(.*)\/(foo1|foo2|foo3)$');

        expect(query).toEqual(expectedRegex);
      });
    });

    describe("clientQuery", function() {
      it("builds a query for a router and metric and all clients", function() {
        var query = Query.clientQuery().withRouter(routerName).withMetric("fooMetric").allClients().build();
        var expectedRegex = new RegExp('^rt\/(fooRouter)\/dst\/id\/(.*)\/(fooMetric)$');
        expect(query).toEqual(expectedRegex);
      });

      it("builds a query for a router and metric and a client", function() {
        var query = Query.clientQuery().withRouter(routerName).withMetric("fooMetric").withClient("fooClient").build();
        var expectedRegex = new RegExp('^rt\/(fooRouter)\/dst\/id\/(fooClient)\/(fooMetric)$');

        expect(query).toEqual(expectedRegex);
      });

      it("builds a query for a router and metric and several clients", function() {
        var query = Query.clientQuery().withRouter(routerName).withMetric("fooMetric").withClients(["foo1", "foo2", "bar1"]).build();
        var expectedRegex = new RegExp('^rt\/(fooRouter)\/dst\/id\/(foo1|foo2|bar1)\/(fooMetric)$');

        expect(query).toEqual(expectedRegex);
      });
    });

    describe("serverQuery", function() {
      it("builds a query for a router and metric and all servers", function() {
        var query = Query.serverQuery().withRouter(routerName).withMetric("fooMetric").allServers().build();
        var expectedRegex = new RegExp('^rt\/(fooRouter)\/srv\/(.*)\/(fooMetric)$');

        expect(query).toEqual(expectedRegex);
      });

      it("builds a query for a router and a metric and a server", function() {
        var query = Query.serverQuery().withRouter(routerName).withMetric("fooMetric").withServer("barServer").build();
        var expectedRegex = new RegExp('^rt\/(fooRouter)\/srv\/(barServer)\/(fooMetric)$');

        expect(query).toEqual(expectedRegex);
      });

      it("builds a query for a router and all metrics and a server", function() {
        var query = Query.serverQuery().withRouter(routerName).allMetrics().withServer("quuxServer").build();
        var expectedRegex = new RegExp('^rt\/(fooRouter)\/srv\/(quuxServer)\/(.*)$');

        expect(query).toEqual(expectedRegex);
      });

      it("builds a query for a router and several servers", function() {
        var query = Query.serverQuery().withRouter(routerName).withServers(["bar1", "bar2", "bar3"]).build();
        var expectedRegex = new RegExp('^rt\/(fooRouter)\/srv\/(bar1|bar2|bar3)\/(.*)$');

        expect(query).toEqual(expectedRegex);
      });
    });

    describe("find", function() {
      it("finds the metrics that match the given query", function() {
        var query = Query.clientQuery().withRouter("fooRouter").allMetrics().build();
        var rawMetrics = [
        ];
      });
    });
  });
});
