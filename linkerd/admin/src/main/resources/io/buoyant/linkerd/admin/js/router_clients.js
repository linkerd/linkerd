var RouterClient = (function() {
  var template;
  var colorLookup;
  var colorIdx = 0;

  function getMetricDefinitions(routerName, clientName) {
    return _.map(["requests", "connections", "success", "failures"], function(metric) {
      return {
        metricSuffix: metric,
        query: Query.clientQuery().withRouter(routerName).withClient(clientName).withMetric(metric).build()
      }
    });
  }

  function renderMetrics($container, client, summaryData, latencyData) {
    var clientHtml = template($.extend({
      client: client.label,
      latencies: latencyData
    }, summaryData));
    var $clientHtml = $("<div />").addClass("router-client").html(clientHtml);

    $container.html($clientHtml);
  }

  function getLatencyData(client, latencyKeys) {
    var latencyData = _.pick(client.metrics, latencyKeys);
    var tableData = {};
    var chartData = [];

    _.each(latencyData, function(latencyValue, metricName) {
      tableData[metricName.split(".")[1]] = latencyValue;
      chartData.push({
        name: metricName,
        delta: latencyValue
      });
    });

    return { tableData: tableData, chartData: chartData };
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

  function timeseriesParams() {
    return {
      strokeStyle: colorLookup[colorIdx++ % colorLookup.length], //TODO: #242 per client color scheme
      lineWidth: 2
    };
  };

  function initializeChart($chartEl, latencyKeys) {
    var $canvas = $("<canvas id='canvas' height='181'></canvas>");
    $chartEl.append($canvas);

    var chart = new UpdateableChart(
      {
        minValue: 0,
        grid: {
          strokeStyle: '#878787',
          verticalSections: 1,
          millisPerLine: 10000,
          borderVisible: false
        },
        labels: {
          fillStyle: '#878787',
          fontSize: 12,
          precision: 0
        },
        millisPerPixel: 60
      },
      $canvas[0],
      function() {
        return $chartEl.width();
      },
      timeseriesParams
    );
    var desiredLatencyMetrics = _.map(latencyKeys, function(metric) {
      return {
        name: metric,
        color: ""
      }
    });
    chart.setMetrics(desiredLatencyMetrics, timeseriesParams, true);

    return chart;
  }

  return function (metricsCollector, routers, client, $metricsEl, routerName, clientTemplate, $chartEl, colors) {
    template = clientTemplate;
    colorLookup = colors;
    var metricDefinitions = getMetricDefinitions(routerName, client.label);
    var latencyKeys = Query.filter(/^request_latency_ms\.(max|min|p9990|p99|p95|p50)$/, _.keys(client.metrics));

    renderMetrics($metricsEl, client, [], []);
    var chart = initializeChart($chartEl, latencyKeys);

    var metricsHandler = function(data) {
      var filteredData = _.filter(data.specific, function (d) { return d.name.indexOf(routerName) !== -1 });
      var summaryData = getSummaryData(filteredData, metricDefinitions);
      var latencies = getLatencyData(client, latencyKeys);

      chart.updateMetrics(latencies.chartData);
      renderMetrics($metricsEl, client, summaryData, latencies.tableData);
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
  return function (metricsCollector, routers, $clientEl, routerName, clientTemplate, colors) {
    var clients = routers.clients(routerName);
    routers.onAddedClients(addClients);

    _.map(clients, initializeClient);

    function initializeClient(client) {
      var $metrics = $("<div />").addClass("col-md-6").appendTo($clientEl);
      var $chart = $("<div />").addClass("col-md-6").appendTo($clientEl);

      RouterClient(metricsCollector, routers, client, $metrics, routerName, clientTemplate, $chart, colors);
    }

    function addClients(addedClients) {
      _.chain(addedClients)
        .filter(function(client) { return client.router === routerName })
        .each(initializeClient)
        .value();
    }
  }
})();
