"use strict";

define([
  'jQuery',
  'src/utils'
], function($, Utils) {
  var SuccessRateGraph = (function() {
    var neutralLineColor = "#878787"; // greys.neutral
    var defaultWidth = 1181;
    var colMd6Width = 594 + 15; // from our css grid layout spec + padding

    // set default y range such that a graph of purely 100% success rate doesn't
    // blend in to the very top of the graph, and doesn't center
    var yRangeDefaultMin = 99.99;
    var yRangeDefaultMax = 100.001;

    function timeseriesParamsFn(successLineColor) {
      return function(_name) {
        return {
          strokeStyle: successLineColor,
          lineWidth: 2
        };
      };
    }

    function chartWidthFn() {
      var serverWidth = $(".router-row-container").width();
      if (serverWidth < defaultWidth) {
        return serverWidth;
      }

      return serverWidth - colMd6Width;
    }

    function yRangeFunction(range) {
      var min = range.min === 100 ? yRangeDefaultMin : range.min;
      return { min: min, max: yRangeDefaultMax };
    }

    function initializeChart($chartEl, tsOpts, chartWidthFn) {
      var $canvas = $("<canvas id='client-success-canvas' height='141'></canvas>");
      $chartEl.append($canvas);

      var chart = new Utils.UpdateableChart(
        {
          yRangeFunction: yRangeFunction,
          grid: {
            strokeStyle: neutralLineColor,
            verticalSections: 2,
            millisPerLine: 10000,
            borderVisible: false
          },
          horizontalLines:[ // draw a 100 % Success Rate reference line
            { color: neutralLineColor, lineWidth:1, value: 100 }
          ],
          labels: {
            fillStyle: neutralLineColor,
            fontSize: 12,
            precision: 2
          },
          millisPerPixel: 60
        },
        $canvas[0],
        chartWidthFn,
        tsOpts
      );

      chart.setMetrics([{ name: "successRate" }]);

      return chart;
    }

    return function($chartEl, clientColor) {
      var chart = initializeChart($chartEl, timeseriesParamsFn(clientColor), chartWidthFn);

      return {
        updateMetrics: function(data) {
          chart.updateMetrics(data)
        }
      }
    }
  })();
  return SuccessRateGraph;
});
