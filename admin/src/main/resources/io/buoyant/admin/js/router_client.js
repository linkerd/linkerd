/* globals SuccessRateGraph, Query, SuccessRate */
/* exported RouterClient */
var RouterClient = (function() {
  var template;
  var metricToColorShade = {
    "max": "light",
    "p9990": "tint",
    "p99": "neutral",
    "p95": "shade",
    "p50": "dark"
  }
  var latencyKeys = _.map(metricToColorShade, function(val, key) { return "request_latency_ms." + key });
  function createLatencyLegend(colorLookup) {
    return _.mapValues(metricToColorShade, function(shade) {
      return colorLookup[shade];
    });
  }

  function getMetricDefinitions(routerName, clientName) {
    return _.map([
        {suffix: "requests", label: "Requests"},
        {suffix: "connections", label: "Connections"},
        {suffix: "success", label: "Successes"},
        {suffix: "failures", label: "Failures"}
      ], function(metric) {
      return {
        metricSuffix: metric.suffix,
        label: metric.label,
        query: Query.clientQuery().withRouter(routerName).withClient(clientName).withMetric(metric.suffix).build()
      }
    });
  }

  function renderMetrics($container, client, summaryData, latencyData, clientColor) {
    var clientHtml = template({
      clientColor: clientColor,
      client: client.label,
      latencies: latencyData,
      data: summaryData
    });
    var $clientHtml = $("<div />").addClass("router-client").html(clientHtml);

    $container.html($clientHtml);
  }

  function getLatencyData(client, latencyKeys, chartLegend) {
    var latencyData = _.pick(client.metrics, latencyKeys);

    return _.map(latencyData, function(latencyValue, metricName) {
      var key = metricName.split(".")[1];
      return {
        latencyLabel: key,
        latencyValue: latencyValue,
        latencyColor: chartLegend[key]
      };
    });
  }

  function getSuccessRate(summaryData) {
    var successRate = summaryData.successRateRaw === -1 ? 1 : summaryData.successRateRaw;
    return [{ name: "successRate", delta: successRate * 100 }];
  }

  function getSummaryData(data, metricDefinitions) {
    var summary = _.reduce(metricDefinitions, function(mem, defn) {
      var clientData = Query.filter(defn.query, data);
      mem[defn.metricSuffix] = {
        description: defn.label,
        value: _.isEmpty(clientData) ? null : clientData[0].delta
      };

      return mem;
    }, {});

    var successRate = new SuccessRate(summary.success.value || 0, summary.failures.value || 0);
    summary.successRate = {
      description: "Success Rate",
      value: successRate.prettyRate(),
      style: successRate.rateStyle()
    };
    summary.successRateRaw = successRate.successRate;

    return summary;
  }

  return function (metricsCollector, routers, client, $metricsEl, routerName, clientTemplate, metricPartial, $chartEl, colors) {
    template = clientTemplate;
    Handlebars.registerPartial('metricPartial', metricPartial);
    var clientColor = colors.color;
    var latencyLegend = createLatencyLegend(colors.colorFamily);
    var metricDefinitions = getMetricDefinitions(routerName, client.label);

    renderMetrics($metricsEl, client, [], [], clientColor);
    var chart = SuccessRateGraph($chartEl, colors.color);

    var metricsHandler = function(data) {
      var filteredData = _.filter(data.specific, function (d) { return d.name.indexOf(routerName) !== -1 });
      var summaryData = getSummaryData(filteredData, metricDefinitions);
      var latencies = getLatencyData(client, latencyKeys, latencyLegend); // this legend is no longer used in any charts: consider removing

      chart.updateMetrics(getSuccessRate(summaryData));
      renderMetrics($metricsEl, client, summaryData, latencies, clientColor);
    }

    var getDesiredMetrics = function(metrics) {
      return  _.flatMap(metricDefinitions, function(d) {
        return Query.filter(d.query, metrics);
      });
    }

    metricsCollector.registerListener(metricsHandler, getDesiredMetrics);

    return {
      updateColors: function(clientToColor) {
        chart.updateColors(clientToColor[client.label].color);
      }
    };
  };
})();
