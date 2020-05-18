"use strict";

define([
  'jQuery',
  'lodash',
  'src/bar_chart'
], function($, _, BarChart) {
  describe("Simple bar chart", function() {
    var $lbContainer;
    var lbBarChart;
    var containerWidth = 100;
    var fakeDescription = "Bar chart description";
    var fakeValue = "9876";

    function barChartColorFn(percent) {
      return percent < 1/3 ? "red" : percent < 2/3 ? "orange" : "green";
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

    function createContainer(width) {
      return $("<div style='width:" + width + "px;' />");
    }

    function createBarChart($container, colorFn) {
      return new BarChart($container, colorFn);
    }

    function getBarContainer($ctr) {
      return $ctr.find(".overlay-bars.bar-container");
    }

    function getBar($ctr) {
      return $ctr.find(".overlay-bars.bar");
    }

    beforeEach(function() {
      $lbContainer = createContainer(containerWidth);
      lbBarChart = createBarChart($lbContainer, barChartColorFn);
    });

    it("renders a bar chart with color and labels", function() {
      lbBarChart.update(generateData(60, 200));
      var $barContainer = getBarContainer($lbContainer);
      var $bar = getBar($lbContainer);

      expect($lbContainer.find(".bar-chart-label").text()).toContain(fakeDescription);
      expect($lbContainer.find(".bar-chart-label").text()).toContain(fakeValue);

      expect($barContainer.attr('class')).toContain("red");
      expect($barContainer.width()).toBe(containerWidth);
      expect($bar.width() / $barContainer.width()).toBeCloseTo(60/200);
    });

    it("updates the bar's color when new data is added", function() {
      lbBarChart.update(generateData(21, 30));
      var $barContainer = getBarContainer($lbContainer);

      expect($barContainer.attr('class')).toContain("green");

      lbBarChart.update(generateData(19, 30));
      $barContainer = getBarContainer($lbContainer);

      expect($barContainer.attr('class')).toContain("orange");

      lbBarChart.update(generateData(6, 30));
      $barContainer = getBarContainer($lbContainer);

      expect($barContainer.attr('class')).toContain("red");

      lbBarChart.update(generateData(40, 30));
      $barContainer = getBarContainer($lbContainer);

      expect($barContainer.attr('class')).toContain("green");
    });

    it("correctly reflects the percentage in the chart's width", function() {
      var wideWidth = 620;
      var $wideContainer = createContainer(wideWidth);
      var weirdChart = createBarChart($wideContainer, barChartColorFn);

      weirdChart.update(generateData(86, 100));
      var $barContainer = getBarContainer($wideContainer);
      var $bar = getBar($wideContainer);

      expect($barContainer.width()).toBe(wideWidth);
      expect($bar.width() / $barContainer.width()).toBeCloseTo(0.86);

      weirdChart.update(generateData(13, 100));
      $barContainer = getBarContainer($wideContainer);
      $bar = getBar($wideContainer);

      expect($barContainer.width()).toBe(wideWidth);
      expect($bar.width() / $barContainer.width()).toBeCloseTo(0.13);
    });

    it("doesn't overflow the container bar for percents > 100%", function() {
      lbBarChart.update(generateData(21, 30));
      var $barContainer = getBarContainer($lbContainer);
      var $bar = getBar($lbContainer);

      expect($barContainer.width()).toBe(containerWidth);
      expect($bar.width() / $barContainer.width()).toBeCloseTo(21/30);

      lbBarChart.update(generateData(60, 30));
      $barContainer = getBarContainer($lbContainer);
      $bar = getBar($lbContainer);

      expect($barContainer.width()).toBe(containerWidth);
      expect($bar.width()).toBe(containerWidth);
    });
  });
});
