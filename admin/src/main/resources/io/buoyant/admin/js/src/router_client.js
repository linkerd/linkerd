"use strict";

define([
  'jQuery',
  'lodash',
  'Handlebars',
  'src/utils',
  'src/query',
  'src/success_rate_graph',
  'text!template/metric.partial.template',
  'text!template/router_client.template'
], function($, _, Handlebars,
  Utils,
  Query,
  SuccessRateGraph,
  metricPartialTemplate,
  routerClientTemplate) {
  var RouterClient = (function() {
    var template = Handlebars.compile(routerClientTemplate);

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

      var successRate = new Utils.SuccessRate(summary.success.value || 0, summary.failures.value || 0);
      summary.successRate = {
        description: "Success Rate",
        value: successRate.prettyRate(),
        style: successRate.rateStyle()
      };
      summary.successRateRaw = successRate.successRate;

      return summary;
    }

    return function (metricsCollector, routers, client, $metricsEl, routerName, $chartEl, colors, $toggleLinks, shouldExpandInitially) {
      var metricPartial = Handlebars.compile(metricPartialTemplate);
      Handlebars.registerPartial('metricPartial', metricPartial);

      var clientColor = colors.color;
      var latencyLegend = createLatencyLegend(colors.colorFamily);
      var metricDefinitions = getMetricDefinitions(routerName, client.label);

      var $expandLink = $toggleLinks.find(".client-expand");
      var $collapseLink = $toggleLinks.find(".client-collapse");

      renderMetrics($metricsEl, client, [], [], clientColor);
      var chart = SuccessRateGraph($chartEl.find($(".client-success-rate")), colors.color);

      // collapse client section by default (deal with large # of clients)
      if(shouldExpandInitially) {
        toggleClientDisplay(true);
      } else {
        toggleClientDisplay(false);
      }

      $expandLink.click(function() { toggleClientDisplay(true); });
      $collapseLink.click(function() { toggleClientDisplay(false); });

      function toggleClientDisplay(expand) {
        if (expand) {
          metricsCollector.registerListener(metricsHandler, getDesiredMetrics);
        } else {
          metricsCollector.deregisterListener(metricsHandler);
        }
        $metricsEl.toggle(expand);
        $chartEl.toggle(expand);
        $collapseLink.toggle(expand);
        $expandLink.toggle(!expand);
      }

      function metricsHandler(data) {
        var filteredData = _.filter(data.specific, function (d) { return d.name.indexOf(routerName) !== -1 });
        var summaryData = getSummaryData(filteredData, metricDefinitions);
        var latencies = getLatencyData(client, latencyKeys, latencyLegend); // this legend is no longer used in any charts: consider removing

        chart.updateMetrics(getSuccessRate(summaryData));
        renderMetrics($metricsEl, client, summaryData, latencies, clientColor);
      }

      function getDesiredMetrics(metrics) {
        return  _.flatMap(metricDefinitions, function(d) {
          return Query.filter(d.query, metrics);
        });
      }

      return {
        updateColors: function(clientToColor) {
          chart.updateColors(clientToColor[client.label].color);
        }
      };
    };
  })();
  return RouterClient;
});
