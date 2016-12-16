describe("Query", function() {
  var Query = require('../js/query.js');
  var routerName = "fooRouter";

  describe("generalQuery", function() {
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
  });

  describe("clientQuery", function() {
    it("should fail on a failure", function() {
      expect(1 + 1).toBe(2);
    });

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
    it("doesn't build a query that has both clients and servers", function() {
      var badQuery = function() { return Query.clientQuery().withRouter("").withMetric("").withClient("").withServer("").build(); };
      var expectedRegex = new RegExp('^rt\/(fooRouter)\/dst\/id\/(fooClient)\/(fooMetric)$');

      expect(badQuery).toThrowError(TypeError);
    })
  });
});
