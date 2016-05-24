/* globals Query, SuccessRate */
/* exported RouterServers */
var RouterServer = (function() {
  var template;

  function getMetricDefinitions(routerName, serverName) {
    return [
      {
        description: "Requests",
        metricSuffix: "requests",
        query: Query.serverQuery().withRouter(routerName).withServer(serverName).withMetric("requests").build()
      },
      {
        description: "Pending",
        metricSuffix: "load",
        query: Query.serverQuery().withRouter(routerName).withServer(serverName).withMetric("load").build()
      },
      {
        description: "Successes",
        metricSuffix: "success",
        query: Query.serverQuery().withRouter(routerName).withServer(serverName).withMetric("success").build(),
        getRate: function(data) {
          var successRate = new SuccessRate(data.success || 0, data.failures || 0);
          return successRate.prettyRate();
        }
      },
      {
        description: "Failures",
        metricSuffix: "failures",
        query: Query.serverQuery().withRouter(routerName).withServer(serverName).withMetric("failures").build()
      }
    ];
  }

  function renderServer($container, server, data) {
    var metrics = _.reduce(data, function(metrics, d) {
      metrics[d.metricSuffix] = {
        description: d.description,
        value: d.value,
        rate: d.rate
      };
      return metrics;
    }, {});

    $container.html(template({
      server: server.label,
      metrics: metrics
    }));
  }

  function processData(data, router, server) {
    var lookup = {}; // track # requests for SuccessRate()
    var metricDefinitions = getMetricDefinitions(router, server);

    return _.map(metricDefinitions, function(defn) {
      var serverData = Query.filter(defn.query, data);

      if (!_.isEmpty(serverData)) {
        defn.value = serverData[0].delta;
        lookup[defn.metricSuffix] = defn.value;

        if(_.isFunction(defn.getRate)) {
          defn.rate = defn.getRate(lookup);
        }
      }

      return defn;
    });
  }
  return function (metricsCollector, server, $serverEl, routerName, serverTemplate) {
    template = serverTemplate;

    var metricsHandler = function(data) {
      var filteredData = _.filter(data.specific, function (d) { return d.name.indexOf(routerName) !== -1 });
      var transformedData = processData(filteredData, routerName, server.label);
      renderServer($serverEl, server, transformedData);
    }

    var getDesiredMetrics = function(metrics) {
      var metricDefinitions = getMetricDefinitions(routerName, server.label);
      return _.flatMap(metricDefinitions, function(d) {
        return Query.filter(d.query, metrics);
      });
    }

    metricsCollector.registerListener(metricsHandler, getDesiredMetrics);

    return {};
  };
})();

var RouterServers = (function() {
  return function (metricsCollector, routers, $serverEl, routerName, serverTemplate, rateMetricPartial) {
    var servers = routers.servers(routerName);
    Handlebars.registerPartial('rateMetricPartial', rateMetricPartial);

    _.map(servers, function(server) {
      var $el = $("<div />").addClass("router-server");
      $serverEl.append($el);
      RouterServer(metricsCollector, server, $el, routerName, serverTemplate);
    });
  }
})();
