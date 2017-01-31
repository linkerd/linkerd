"use strict";

define([
  'jQuery',
  'lodash',
  'Handlebars',
  'text!template/barchart.template'
], function($, _, Handlebars, barChartTemplate) {

  var template = Handlebars.compile(barChartTemplate);
  var barContainerWidth = 360; // px

  var defaultDisplayData = {
    percent: null,
    label: {
      description: "",
      value: null
    },
    warningLabel: null
  };
  var defaultTmplData = {
    color: "",
    barWidth: 0,
    barContainerWidth: barContainerWidth
  };

  function render($container, data, getBarDimensions) {
    if (!data) return;
    var tmplData = getBarDimensions(data);
    $container.html(template(tmplData));
  }

  return function($container, getColor) {
    barContainerWidth = $container.width() || barContainerWidth;
    render($container, null, getBarDimensions);

    function getBarDimensions(displayData) {
      if (!displayData) return _.merge({}, defaultDisplayData, defaultTmplData);
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
