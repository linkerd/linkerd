"use strict";

define([
  'jQuery',
  'lodash',
  'src/bar_chart'
], function($, _, BarChart) {
  describe("Simple bar chart", function() {
    it("renders a bar chart (one bar color) with correct widths", function() {
      var containerWidth = 100;
      var leftValue = 60;
      var rightValue = 200;
      var fakeDescription = "Bar chart description";
      var fakeValue = "9876";

      var $lbContainer = $("<div style='width:" + containerWidth + "px;' />");

      function barChartColorFn(percent) {
        return percent < 0.5 ? "orange" : "green";
      }

      function barPercentCalc(data) {
        var percent = !data.denom ? null : data.numer / data.denom;

        return {
          percent: percent,
          label: {
            description: fakeDescription,
            value: fakeValue
          }
        }
      }
      var lbBarChart = new BarChart($lbContainer, barPercentCalc, barChartColorFn);
      lbBarChart.update({ numer: leftValue, denom: rightValue });

      var $barContainer = $lbContainer.find(".overlay-bars.bar-container");
      var $bar = $lbContainer.find(".overlay-bars.bar");

      expect($lbContainer.find(".bar-chart-label").text()).toContain(fakeDescription);

      expect($barContainer.attr('class')).toContain("orange");
      expect($barContainer.width()).toBe(containerWidth);
      expect($bar.width()).toBe(Math.round(leftValue/rightValue * containerWidth));

      lbBarChart.update({ numer: 90, denom: 100 });
      $bar = $lbContainer.find(".overlay-bars.bar");
      $barContainer = $lbContainer.find(".overlay-bars.bar-container");
      expect($barContainer.attr('class')).toContain("green");
      expect($bar.width()).toBe(Math.round(90/100 * containerWidth));

      lbBarChart.update({ numer: 6, denom: 30 });
      $bar = $lbContainer.find(".overlay-bars.bar");
      $barContainer = $lbContainer.find(".overlay-bars.bar-container");
      expect($barContainer.attr('class')).toContain("orange");
      expect($bar.width()).toBe(Math.round(6/30 * containerWidth));
    });
  });
});
