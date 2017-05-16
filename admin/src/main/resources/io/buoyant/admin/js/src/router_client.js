"use strict";

define([
  'jQuery',
  'lodash',
  'handlebars.runtime',
  'src/latency_color_util',
  'src/utils',
  'src/success_rate_graph',
  'src/bar_chart',
  'template/compiled_templates'
], function($, _, Handlebars,
  LatencyUtil,
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

      var latencyPartial = templates["latencies.partial"];
      Handlebars.registerPartial('latencyPartial', latencyPartial);

      var $contentContainer = $container.find(".client-content-container");

      var $headerLine = $container.find(".header-line");
      var colorBorder = "2px solid" + colors.color;

      var $metricsEl = $container.find(".metrics-container");
      var $chartEl = $container.find(".chart-container");
      var $toggleLinks = $container.find(".client-toggle");
      var $lbBarChart = $container.find(".lb-bar-chart");

      var latencyLegend = LatencyUtil.createLatencyLegend(colors.colorFamily);
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
          metricsCollector.registerListener(getClientId(routerName, client), metricsHandler);
        } else {
          $contentContainer.css({'border': null});
          $headerLine.css({'border-bottom': colorBorder});

          combinedClientGraph.ignoreClient(client);
          metricsCollector.deregisterListener(getClientId(routerName, client));
        }

        $contentContainer.toggle(expand);
        $collapseLink.toggle(expand);
        $expandLink.toggle(!expand);
      }

      function getClientId(router, client) {
        return "RouterClient_" + router + "_" + client;
      }

      function metricsHandler(data) {
        var summaryData = getSummaryData(data, metricDefinitions);
        var latencyData = _.get(data, ["rt", routerName, "client", client, "request_latency_ms"]);
        var latencies = LatencyUtil.getLatencyData(latencyData, latencyLegend);

        successRateChart.updateMetrics(getSuccessRate(summaryData));
        lbBarChart.update(summaryData);

        renderMetrics($metricsEl, client, summaryData, latencies);
      }

      return {
        label: client,
        expireClient: function() {
          // when metrics are pruned, kill this client
          combinedClientGraph.expireClient(client);
          toggleClientDisplay(false);
        },
        unexpireClient: function(shouldExpand) {
          // there is a difference between whether a client is toggled/not vs
          // expired/not (completely hidden)
          combinedClientGraph.unexpireClient(client);
          toggleClientDisplay(shouldExpand);
        },
        isExpired: false
      };
    };
  })();
  return RouterClient;
});
