"use strict";

define([
  'jQuery',
  'lodash',
  'src/bar_chart'
], function($, _, BarChart) {
  describe("LB/retries bar chart", function() {
    it("renders a simple bar chart (one bar color)", function() {
      var containerWidth = 100;
      var leftValue = 60;
      var rightValue = 200;

      var $lbContainer = $("<div style='width:" + containerWidth + "px;' />");
      var lbBarChart = new BarChart($lbContainer);
      lbBarChart.update({
        "loadbalancer/size": { description: "fooMin", value: rightValue },
        "loadbalancer/available": { description: "fooMax", value: leftValue }
      });

      var leftNum = $lbContainer.find(".bar-chart-value.pull-left").text();
      var rightNum = $lbContainer.find(".bar-chart-value.pull-right").text();
      var $barContainer = $lbContainer.find(".overlay-bars.bar-container");
      var $bar = $lbContainer.find(".overlay-bars.bar");

      expect(leftNum).toContain(leftValue);
      expect(rightNum).toContain(rightValue);

      expect($barContainer.attr('class')).toContain("orange");
      expect($barContainer.width()).toBe(containerWidth);
      expect($bar.width()).toBe(Math.round(leftValue/rightValue * containerWidth));

      lbBarChart.update({
        "loadbalancer/size": { description: "fooMin", value: 100 },
        "loadbalancer/available": { description: "fooMax", value: 90 }
      });
      $bar = $lbContainer.find(".overlay-bars.bar");
      expect($bar.width()).toBe(Math.round(90/100 * containerWidth));

      lbBarChart.update({
        "loadbalancer/size": { description: "fooMin", value: 30 },
        "loadbalancer/available": { description: "fooMax", value: 6 }
      });
      $bar = $lbContainer.find(".overlay-bars.bar");
      expect($bar.width()).toBe(Math.round(6/30 * containerWidth));
    });

    it("renders a three colored bar chart", function() {
      var containerWidth = 100;
      var leftValue = 50;
      var rightValue = 100;

      var $retriesContainer = $("<div style='width:" + containerWidth + "px;' />");
      var retriesChart = new BarChart($retriesContainer, true);
      retriesChart.update({
        "retries/requeues": { description: "fooMin", value: leftValue },
        "requests": { description: "fooMax", value: rightValue }
      });

      var leftNum = $retriesContainer.find(".bar-chart-value.pull-left").text();
      var rightNum = $retriesContainer.find(".bar-chart-value.pull-right").text();

      var $yellow = $retriesContainer.find(".tricolor-overlay .bar.yellow");
      var $orange = $retriesContainer.find(".tricolor-overlay .bar.orange");
      var $red = $retriesContainer.find(".tricolor-overlay .bar.red");

      expect(leftNum).toContain(leftValue);
      expect(rightNum).toContain(rightValue);

      expect($yellow.width()).toBe(33);
      expect($orange.width()).toBe(17);
      expect($red.width()).toBe(0);

      retriesChart.update({
        "retries/requeues": { description: "fooMin", value: 90 },
        "requests": { description: "fooMax", value: 100 }
      });
      $yellow = $retriesContainer.find(".tricolor-overlay .bar.yellow");
      $orange = $retriesContainer.find(".tricolor-overlay .bar.orange");
      $red = $retriesContainer.find(".tricolor-overlay .bar.red");

      expect($yellow.width()).toBe(33);
      expect($orange.width()).toBe(33);
      expect($red.width()).toBe(24);
    });
  });
});
