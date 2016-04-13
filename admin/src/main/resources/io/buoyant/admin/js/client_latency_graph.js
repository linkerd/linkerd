var ClientLatencyGraph = (function() {
  const metricToColorShade = {
    "max": "light",
    "p9990": "tint",
    "p99": "neutral",
    "p95": "shade",
    "p50": "dark"
  }

  function createChartLegend(colorLookup) {
    return _.mapValues(metricToColorShade, function(shade) {
      return colorLookup[shade];
    });
  }

  function initializeChart($chartEl, latencyKeys, timeseriesParamsFn) {
    var $canvas = $("<canvas id='canvas' height='141'></canvas>");
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
      timeseriesParamsFn
    );
    var desiredLatencyMetrics = _.map(latencyKeys, function(metric) {
      return {
        name: metric,
        color: ""
      }
    });
    chart.setMetrics(desiredLatencyMetrics, true);

    return chart;
  }

  return function($chartEl, colors) {
    var chartLegend = createChartLegend(colors);
    var latencyKeys = _.map(metricToColorShade, function(val, key) { return "request_latency_ms." + key });
    var chart = initializeChart($chartEl, latencyKeys, timeseriesParams);

    function timeseriesParams(name) {
      return {
        strokeStyle: chartLegend[name.replace("request_latency_ms.", "")],
        lineWidth: 2
      };
    };

    return {
      updateColors: function(colors) {
        chartLegend = createChartLegend(colors);
      },
      getChartLegend: function() { return chartLegend; },
      getLatencyKeys: function() { return latencyKeys; },
      updateMetrics: function(data) { chart.updateMetrics(data) }
    }
  };
})();
