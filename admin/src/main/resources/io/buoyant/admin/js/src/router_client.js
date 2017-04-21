"use strict";

define([
  'jQuery',
  'lodash',
  'handlebars.runtime',
  'src/utils',
  'src/success_rate_graph',
  'src/bar_chart',
  'template/compiled_templates'
], function($, _, Handlebars,
  Utils,
  SuccessRateGraph,
  BarChart,
  templates
) {

  var LoadBalancerBarChart = function($lbContainer) {
    function getColor(percent) {
      return percent < 0.5 ? "orange" : "green";
    }

    function display(value) {
      return _.isNumber(value) ? value : "-";
    }

    function getPercent(data) {
      if (!data) return null;

      var numer = data["loadbalancer/available"] || {};
      var denom = data["loadbalancer/size"] || {};
      var percent = (!denom || !denom.value) ? 0 : (numer.value || 0) / denom.value;

      return {
        percent: percent,
        label: {
          description: "Endpoints available",
          value: display(numer.value) + " / " + display(denom.value)
        }
      }
    }

    var barChart = new BarChart($lbContainer, getColor);
    return {
      update: function(data) {
        return barChart.update(getPercent(data));
      }
    }
  }

  var RouterClient = (function() {
    var template = templates.router_client;

    var metricToColorShade = {
      "max": "light",
      "p9990": "tint",
      "p99": "neutral",
      "p95": "shade",
      "p50": "dark"
    }
    var latencyKeys = _.keys(metricToColorShade);

    function createLatencyLegend(colorLookup) {
      return _.mapValues(metricToColorShade, function(shade) {
        return colorLookup[shade];
      });
    }

    function getMetricDefinitions(routerName, clientName) {
      return _.map([
        {suffix: "requests", label: "Requests"},
        {suffix: "connections", label: "Connections", isGauge: true},
        {suffix: "success", label: "Successes"},
        {suffix: "failures", label: "Failures"},
        {suffix: "loadbalancer/size", label: "Load balancer pool size", isGauge: true},
        {suffix: "loadbalancer/available", label: "Load balancers available", isGauge: true}
      ], function(metric) {
        var treeKeyRoot = ["rt", routerName, "client", clientName].concat(metric.suffix.split("/"));

        return {
          metricSuffix: metric.suffix,
          label: metric.label,
          isGauge: metric.isGauge,
          treeMetricRoot: treeKeyRoot
        }
      });
    }

    function renderMetrics($container, client, summaryData, latencyData) {
      var clientHtml = template({
        client: client,
        latencies: latencyData,
        data: summaryData
      });
      var $clientHtml = $("<div />").addClass("router-client").html(clientHtml);

      $container.html($clientHtml);
    }

    function getLatencyData(data, routerName, clientName, chartLegend) {
      var latencyData = _.get(data, ["rt", routerName, "client", clientName, "request_latency_ms"]);

      return _.map(latencyKeys, function(key) {
        return {
          latencyLabel: key,
          latencyValue: _.get(latencyData, "stat." + key),
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
        var clientData = _.get(data, defn.treeMetricRoot);
        var value = _.isEmpty(clientData) ? null : clientData[defn.isGauge ? "gauge" : "delta"];

        mem[defn.metricSuffix] = {
          description: defn.label,
          value: value
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

    return function (metricsCollector, client, $container, routerName, colors, shouldExpandInitially, combinedClientGraph) {
      var metricPartial = templates["metric.partial"];
      Handlebars.registerPartial('metricPartial', metricPartial);

      var $contentContainer = $container.find(".client-content-container");

      var $headerLine = $container.find(".header-line");
      var colorBorder = "2px solid" + colors.color;

      var $metricsEl = $container.find(".metrics-container");
      var $chartEl = $container.find(".chart-container");
      var $toggleLinks = $container.find(".client-toggle");
      var $lbBarChart = $container.find(".lb-bar-chart");

      var latencyLegend = createLatencyLegend(colors.colorFamily);
      var metricDefinitions = getMetricDefinitions(routerName, client);

      var $expandLink = $toggleLinks.find(".client-expand");
      var $collapseLink = $toggleLinks.find(".client-collapse");

      renderMetrics($metricsEl, client, [], []);
      var successRateChart = SuccessRateGraph($chartEl.find(".client-success-rate"), colors.color);
      var lbBarChart = new LoadBalancerBarChart($lbBarChart);

      // collapse client section by default (deal with large # of clients)
      toggleClientDisplay(shouldExpandInitially);

      $expandLink.click(function() { toggleClientDisplay(true); });
      $collapseLink.click(function() { toggleClientDisplay(false); });

      function toggleClientDisplay(expand) {
        if (expand) {
          $contentContainer.css({"border": colorBorder});
          $headerLine.css("border-bottom", "0px");

          combinedClientGraph.unIgnoreClient(client);
          metricsCollector.registerListener(metricsHandler);
        } else {
          $contentContainer.css({'border': null});
          $headerLine.css({'border-bottom': colorBorder});

          combinedClientGraph.ignoreClient(client);
          metricsCollector.deregisterListener(metricsHandler);
        }

        $contentContainer.toggle(expand);
        $collapseLink.toggle(expand);
        $expandLink.toggle(!expand);
      }

      function metricsHandler(data) {
        var summaryData = getSummaryData(data, metricDefinitions);
        var latencies = getLatencyData(data, routerName, client, latencyLegend);

        successRateChart.updateMetrics(getSuccessRate(summaryData));
        lbBarChart.update(summaryData);

        renderMetrics($metricsEl, client, summaryData, latencies);
      }

      return {
        label: client
      };
    };
  })();
  return RouterClient;
});
