/* globals UpdateableChart */
/* exported ClientSuccessRateGraph */
var ClientSuccessRateGraph = (function() {
  var neutralLineColor = "#878787"; // greys.neutral

  function createChartLegend(successLineColor) {
    return {
      referenceLine: neutralLineColor,
      successRate: successLineColor
    }
  }

  function initializeChart($chartEl, dataKeys, timeseriesParamsFn) {
    var $canvas = $("<canvas id='client-success-canvas' height='141'></canvas>");
    $chartEl.append($canvas);

    var chart = new UpdateableChart(
      {
        maxValue: 100.000001, // the 0.000001 is a SmoothieCharts hack
        grid: {
          strokeStyle: '#878787',
          verticalSections: 2,
          millisPerLine: 10000,
          borderVisible: false
        },
        labels: {
          fillStyle: '#878787',
          fontSize: 12,
          precision: 2
        },
        millisPerPixel: 60
      },
      $canvas[0],
      function() {
        return $chartEl.width();
      },
      timeseriesParamsFn
    );
    var desiredMetrics = _.map(dataKeys, function(metric) {
      return {
        name: metric,
        color: ""
      }
    });
    chart.setMetrics(desiredMetrics, true);

    return chart;
  }

  return function($chartEl, clientColor) {
    var chartLegend = createChartLegend(clientColor);
    var successRateKeys = ["successRate", "referenceLine"];
    var chart = initializeChart($chartEl, successRateKeys, timeseriesParams);

    function timeseriesParams(name) {
      return {
        strokeStyle: chartLegend[name],
        lineWidth: name === "referenceLine" ? 1 : 2
      };
    }

    return {
      updateColors: function(clientColor) {
        chartLegend = createChartLegend(clientColor);
      },
      updateMetrics: function(data) {
        chart.updateMetrics(data)
      }
    }
  }
})();
