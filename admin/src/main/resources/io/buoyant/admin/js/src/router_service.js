"use strict";

define([
  'jQuery', 'handlebars.runtime',
  'template/compiled_templates',
  'src/utils',
  'src/colors',
  'src/latency_color_util',
  'src/combined_client_graph',
  'src/success_rate_graph',
  'bootstrap'
], function($, Handlebars,
  templates,
  Utils,
  Colors,
  LatencyUtil,
  CombinedClientGraph,
  SuccessRateGraph
) {

  var desiredServiceMetrics = [
    { description: "Successes", dataKey: "success", accessor: "delta" },
    { description: "Requests", dataKey: "requests", accessor: "delta" },
    { description: "Failures", dataKey: "failures", accessor: "delta" },
    { description: "Pending", dataKey: "pending", accessor: "gauge" }
  ];

  var desiredLatencyColors = Colors.colorOrder[3].colorFamily; // match the success rate graph
  var latencyLegend = LatencyUtil.createLatencyLegend(desiredLatencyColors);

  function getServiceTemplateData(svcMetrics) {
    return _.reduce(desiredServiceMetrics, function(mem, metricSpec) {
      mem[metricSpec.dataKey] = {
        description: metricSpec.description,
        value: _.get(svcMetrics, [metricSpec.dataKey, metricSpec.accessor])
      }
      return mem;
    }, {});
  }

  function getLatencyTemplateData(svcMetrics) {
    return LatencyUtil.getLatencyData(svcMetrics.request_latency_ms, latencyLegend);
  }

  function render(clientData, serviceData, $serviceContainer, $clientContainer) {
    var serviceHtml = templates.router_service_metrics(serviceData);
    var clientsHtml = templates.router_service_client_metrics(clientData);

    $serviceContainer.html(serviceHtml);
    $clientContainer.html(clientsHtml);
  }

  return function(metricsCollector, $routerContainer, router, service, initialData) {
    var metricPartial = templates["metric.partial"];
    Handlebars.registerPartial('metricPartial', metricPartial);
    var latencyPartial = templates["latencies.partial"];
    Handlebars.registerPartial('latencyPartial', latencyPartial);

    var clients = initialData[router].clients;

    var $svcContainer = $(templates.router_service_container({
      service: service
    }));
    $routerContainer.append($svcContainer);

    var $svcMetricsContainer = $($svcContainer.find(".svc-metrics")[0]);
    var $clientsMetricsContainer = $($svcContainer.find(".svc-client-metrics")[0]);

    var clientToColor = Colors.assignColors(clients);

    var $combinedClientGraphEl = $svcContainer.find(".combined-client-graph .router-graph");
    var combinedClientGraph = CombinedClientGraph(null, initialData, router, $combinedClientGraphEl, clientToColor, true);

    var $serviceSuccessGraphEl = $routerContainer.find(".svc-success-graph");
    var serviceSuccessGraph = SuccessRateGraph($serviceSuccessGraphEl, "#4AD8AC");


    function getSuccessGraphMetrics(svcData) {
      var suc = (new Utils.SuccessRate(svcData.success.value || 0, svcData.failures.value || 0)).get();
      var rateToDisplay =  suc  === -1 ? 1 : suc;

      return [{ name: "successRate", delta: rateToDisplay * 100 }];
    }

    function getCombinedClientGraphMetrics(metrics) {
      var clientStats = metrics.clientStats;

      return _.map(clientStats, function(clientData) {
        return {
          name: clientData.client + "/requests",
          delta: clientData.requests
        };
      });
    }

    function onMetricsUpdate(metrics) {
      if (!metrics) {
        metrics = { serviceStats: {}, clientStats: {}};
      }

      var svcTemplateData = getServiceTemplateData(metrics.serviceStats);
      var clientData = {
        colorMap: clientToColor,
        clients: metrics.clientStats
      };

      var serviceData = {
        service: svcTemplateData,
        latencies: getLatencyTemplateData(metrics.serviceStats)
      };

      render(clientData, serviceData, $svcMetricsContainer, $clientsMetricsContainer);
      combinedClientGraph.updateMetrics(getCombinedClientGraphMetrics(metrics));
      serviceSuccessGraph.updateMetrics(getSuccessGraphMetrics(svcTemplateData));
    }

    function onAddedClients(addedClients) {
      clients = _(clients).concat(_.keys(addedClients)).uniq().value();
      clientToColor = Colors.assignColors(clients);

      // pass new colors to combined request graph, add new clients to graph
      combinedClientGraph.updateColors(clientToColor);
      combinedClientGraph.addClients(clients);
    }

    return {
      onMetricsUpdate: onMetricsUpdate,
      onAddedClients: onAddedClients
    }
  };
});
