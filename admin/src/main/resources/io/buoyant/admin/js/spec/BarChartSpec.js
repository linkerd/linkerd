"use strict";

define([
  'jQuery',
  'lodash',
  'src/bar_chart'
], function($, _, BarChart) {
  describe("Simple bar chart", function() {
    it("renders a bar chart (one bar color) with correct widths", function() {
      var containerWidth = 100;
      var fakeDescription = "Bar chart description";
      var fakeValue = "9876";

      var $lbContainer = $("<div style='width:" + containerWidth + "px;' />");

      function barChartColorFn(percent) {
        return percent < 0.5 ? "orange" : "green";
      }

      function generateData(numer, denom) {
        return {
          percent: numer / denom,
          label: {
            description: fakeDescription,
            value: fakeValue
          }
        }
      }
      var lbBarChart = new BarChart($lbContainer, barChartColorFn);
      lbBarChart.update(generateData(60, 200));

      var $barContainer = $lbContainer.find(".overlay-bars.bar-container");
      var $bar = $lbContainer.find(".overlay-bars.bar");

      expect($lbContainer.find(".bar-chart-label").text()).toContain(fakeDescription);

      expect($barContainer.attr('class')).toContain("orange");
      expect($barContainer.width()).toBe(containerWidth);
      expect($bar.width()).toBe(Math.round(60/200 * containerWidth));

      lbBarChart.update(generateData(90, 100));
      $bar = $lbContainer.find(".overlay-bars.bar");
      $barContainer = $lbContainer.find(".overlay-bars.bar-container");
      expect($barContainer.attr('class')).toContain("green");
      expect($bar.width()).toBe(Math.round(90/100 * containerWidth));

      lbBarChart.update(generateData(6, 30));
      $bar = $lbContainer.find(".overlay-bars.bar");
      $barContainer = $lbContainer.find(".overlay-bars.bar-container");
      expect($barContainer.attr('class')).toContain("orange");
      expect($bar.width()).toBe(Math.round(6/30 * containerWidth));
    });
  });
});
