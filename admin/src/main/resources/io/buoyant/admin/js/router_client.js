var RouterClient = (function() {
  var template;

  function getMetricDefinitions(routerName, clientName) {
    return _.map(["requests", "connections", "success", "failures"], function(metric) {
      return {
        metricSuffix: metric,
        query: Query.clientQuery().withRouter(routerName).withClient(clientName).withMetric(metric).build()
      }
    });
  }

  function renderMetrics($container, client, summaryData, latencyData, clientColor) {
    var clientHtml = template($.extend({
      clientColor: clientColor,
      client: client.label,
      latencies: latencyData
    }, summaryData));
    var $clientHtml = $("<div />").addClass("router-client").html(clientHtml);

    $container.html($clientHtml);
  }

  function getLatencyData(client, latencyKeys, chartLegend) {
    var latencyData = _.pick(client.metrics, latencyKeys);
    var tableData = [];
    var chartData = [];

    _.each(latencyData, function(latencyValue, metricName) {
      var key = metricName.split(".")[1];
      tableData.push({
        latencyLabel: key,
        latencyValue: latencyValue,
        latencyColor: chartLegend[key]
      });
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

    var successRate = new SuccessRate(summary.success || 0, summary.failures || 0);
    summary.successRate = successRate.prettyRate();

    return summary;
  }

  return function (metricsCollector, routers, client, $metricsEl, routerName, clientTemplate, $chartEl, colors) {
    template = clientTemplate;
    var clientColor = colors.color;
    var metricDefinitions = getMetricDefinitions(routerName, client.label);

    renderMetrics($metricsEl, client, [], [], clientColor);
    var chart = ClientLatencyGraph($chartEl, colors.colorFamily);

    var metricsHandler = function(data) {
      var filteredData = _.filter(data.specific, function (d) { return d.name.indexOf(routerName) !== -1 });
      var summaryData = getSummaryData(filteredData, metricDefinitions);
      var latencies = getLatencyData(client, chart.getLatencyKeys(), chart.getChartLegend());

      chart.updateMetrics(latencies.chartData);
      renderMetrics($metricsEl, client, summaryData, latencies.tableData, clientColor);
    }

    var getDesiredMetrics = function(metrics) {
      return  _.flatMap(metricDefinitions, function(d) {
        return Query.filter(d.query, metrics);
      });
    }

    metricsCollector.registerListener(metricsHandler, getDesiredMetrics);

    return {
      updateColors: function(clientToColor) {
        chart.updateColors(clientToColor[client.label].colorFamily);
      }
    };
  };
})();
