"use strict";

define([
  'jQuery',
  'lodash',
  'Handlebars',
  'text!template/barchart.template',
  'text!template/singlebar.partial.template',
  'text!template/multibar.partial.template'
], function($, _, Handlebars, barChartTemplate, singleBarPartial, multiBarPartial) {

  var template = Handlebars.compile(barChartTemplate);
  var barContainerWidth = 360; // px

  function render($container, data, dataExtractor) {
    if (!data) return;
    var tmplData = dataExtractor(data);

    $container.html(template(tmplData));
  }

  function getMultiBarDimensions(percent, containerWidth) {
    var triBarWidth = Math.round(containerWidth / 3); // width of each colored section
    var barWidth = Math.round(percent * containerWidth); // width we would display on a singly colored chart
    var barDimensions = {};

    if (percent < 1/3) {
      barDimensions = {
        yellow: barWidth,
        orange: 0,
        red: 0
      };
    } else if (percent < 2/3) {
      barDimensions = {
        yellow: triBarWidth,
        orange: barWidth % triBarWidth,
        red: 0
      };
    } else {
      barDimensions = {
        yellow: triBarWidth,
        orange: triBarWidth,
        red: barWidth % (triBarWidth * 2)
      };
    }

    barDimensions["barContainerWidth"] = containerWidth;
    barDimensions["isMultiBar"] = true;
    return barDimensions;
  }

  function getSingleBarDimensions(percent, containerWidth) {
    return {
      color: percent < 0.5 ? "orange" : "green", // TODO: decide LB color thresholds
      barWidth: Math.round(percent * containerWidth),
      barContainerWidth: containerWidth
    };
  }

  function generateExtractor(dimensionsFn, leftKey, rightKey) {
    return function(data) {
      if (!data) {
        return null;
      }
      var numer = data[leftKey] || {};
      var denom = data[rightKey] || {};

      var percent = (!denom || !denom.value) ? 0 : (numer.value || 0) / denom.value;
      var barData = dimensionsFn(percent, barContainerWidth);

      barData.left = numer;
      barData.right = denom;
      return barData;
    }
  }

  return function($container, isRetriesChart) {
    barContainerWidth = $container.width() || barContainerWidth;
    var dataExtractor;

    if(isRetriesChart) {
      var multiBar = Handlebars.compile(multiBarPartial);
      Handlebars.registerPartial('multiBar', multiBar);
      dataExtractor = generateExtractor(getMultiBarDimensions, "retries/requeues", "requests");
    } else {
      var singleBar = Handlebars.compile(singleBarPartial);
      Handlebars.registerPartial('singleBar', singleBar);
      dataExtractor = generateExtractor(getSingleBarDimensions, "loadbalancer/available", "loadbalancer/size");
    }

    render($container, null, dataExtractor);

    return {
      update: function(newData) {
        render($container, newData, dataExtractor);
      }
    }
  }
});
