var RouterClient = (function() {
  var template;

  function getMetricDefinition(routerName, clientName) {
    return [
      {
        description: "Requests",
        metricSuffix: "requests",
        query: Query.clientQuery().withRouter(routerName).withClient(clientName).withMetric("requests").build()
      },
      {
        description: "Connections",
        metricSuffix: "connections",
        query: Query.clientQuery().withRouter(routerName).withClient(clientName).withMetric("connections").build()
      },
      {
        description: "Successes",
        metricSuffix: "success",
        query: Query.clientQuery().withRouter(routerName).withClient(clientName).withMetric("success").build(),
        getRate: function(data) {
          var successRate = new SuccessRate(data.requests, data.success, data.failures);
          return successRate.prettyRate();
        }
      },
      {
        description: "Failures",
        metricSuffix: "failures",
        query: Query.clientQuery().withRouter(routerName).withClient(clientName).withMetric("failures").build()
      }
    ];
  }

  function renderClient($container, client, data) {
    var clientHtml = template({
      client: client.label,
      metrics: data
    });
    var $clientHtml = ($("<div />").addClass("router-client").html(clientHtml));

    $container.html($clientHtml);
  }

  function processData(data, metricDefinitions) {
    var lookup = {}; // track # requests for SuccessRate()

    return _.map(metricDefinitions, function(defn) {
      var clientData = Query.filter(defn.query, data);

      if (!_.isEmpty(clientData)) {
        defn.value = clientData[0].delta;
        lookup[defn.metricSuffix] = defn.value;

        if(_.isFunction(defn.getRate)) {
          defn.rate = defn.getRate(lookup);
        }
      }

      return defn;
    });
  }

  return function (metricsCollector, routers, client, $clientEl, routerName, clientTemplate) {
    template = clientTemplate;
    var metricDefinitions = getMetricDefinition(routerName, client.label);

    var metricsHandler = function(data) {
      var filteredData = _.filter(data.specific, function (d) { return d.name.indexOf(routerName) !== -1 });
      var transformedData = processData(filteredData, metricDefinitions);

      renderClient($clientEl, client, transformedData);
    }

    var getDesiredMetrics = function(metrics) {
      return _.map(metricDefinitions, function(d) {
        return Query.filter(d.query, metrics);
      });
    }

    metricsCollector.registerListener(metricsHandler, getDesiredMetrics);

    return {};
  };
})();

var RouterClients = (function() {
  return function (metricsCollector, routers, $clientEl, routerName, clientTemplate) {
    var clients = routers.clients(routerName);
    routers.onAddedClients(addClients);

    _.map(clients, initializeClient);

    function initializeClient(client) {
      var $el = $("<div />").addClass("router-client");
      $clientEl.append($el);
      RouterClient(metricsCollector, routers, client, $el, routerName, clientTemplate);
    }

    function addClients(addedClients) {
      _.chain(addedClients)
        .filter(function(client) { return client.router === routerName })
        .each(initializeClient)
        .value();
    }
  }
})();
