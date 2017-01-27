"use strict";

/*
  A simple bar chart.

  Arguments:
    - $container - where you want the bar chart to go
    - percentCalculator - a function that accepts raw metric data, and returns a computed percent, and a label
    - colorFn - fn takes a percent and outputs a string color corresponding to a class in dashboard.css
*/

define([
  'jQuery',
  'lodash',
  'Handlebars',
  'text!template/barchart.template'
], function($, _, Handlebars, barChartTemplate) {

  var template = Handlebars.compile(barChartTemplate);
  var barContainerWidth = 360; // px

  function render($container, data, getBarDimensions) {
    if (!data) return;
    var tmplData = getBarDimensions(data);
    $container.html(template(tmplData));
  }

  return function($container, getColor) {
    barContainerWidth = $container.width() || barContainerWidth;
    render($container, null, getBarDimensions);

    function getBarDimensions(displayData) {
      // var displayData = getPercentAndLabel(data);
      var barWidth = Math.min(Math.round(displayData.percent * barContainerWidth), barContainerWidth);

      var barDimensions = {
        color: getColor(displayData.percent),
        barWidth: barWidth,
        barContainerWidth: barContainerWidth
      }

      return _.merge({}, displayData, barDimensions);
    }

    return {
      update: function(newData) {
        render($container, newData, getBarDimensions);
      }
    }
  }
});
