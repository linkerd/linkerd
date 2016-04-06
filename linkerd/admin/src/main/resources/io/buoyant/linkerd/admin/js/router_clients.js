var RouterClient = (function() {
  var template;

  function getMetricDefinition(routerName, clientName) {
    return _.map(["requests", "connections", "success", "failures"], function(metric) {
      return {
        metricSuffix: metric,
        query: Query.clientQuery().withRouter(routerName).withClient(clientName).withMetric(metric).build()
      }
    });
  }

  function renderClient($container, client, summaryData, latencyData) {
    var clientHtml = template($.extend({
      client: client.label,
      latencies: latencyData
    }, summaryData));
    var $clientHtml = $("<div />").addClass("router-client").html(clientHtml);

    $container.html($clientHtml);
  }

  function getLatencyData(client, latencyKeys) {
    var latencyData = _.pick(client.metrics, latencyKeys);

    return _.mapKeys(latencyData, function(value, key) {
      return key.split(".")[1];
    });
  }

  function getSummaryData(data, metricDefinitions) {
    var summary = _.reduce(metricDefinitions, function(mem, defn) {
      var clientData = Query.filter(defn.query, data);
      mem[defn.metricSuffix] = _.isEmpty(clientData) ? null : clientData[0].delta;

      return mem;
    }, {});

    var successRate = new SuccessRate(summary.requests, summary.success, summary.failures);
    summary.successRate = successRate.prettyRate();

    return summary;
  }

  return function (metricsCollector, routers, client, $clientEl, routerName, clientTemplate) {
    template = clientTemplate;
    var metricDefinitions = getMetricDefinition(routerName, client.label);
    var desiredLatencies = ["max","p9990", "p99", "p95", "p50"];
    var latencyKeys = _.map(desiredLatencies, function(key) { return "request_latency_ms." + key; });

    var metricsHandler = function(data) {
      var filteredData = _.filter(data.specific, function (d) { return d.name.indexOf(routerName) !== -1 });
      var summaryData = getSummaryData(filteredData, metricDefinitions);
      var latencyData = getLatencyData(client, latencyKeys);

      renderClient($clientEl, client, summaryData, latencyData);
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
  return function (metricsCollector, routers, $clientEl, routerName, clientTemplate, colorOrder) {
    CombinedClientGraph(metricsCollector, routerName, $clientEl.find(".router-graph"), colorOrder);

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
