"use strict";

define([
  'jQuery',
  'lodash',
  'spec/fixtures/metrics',
  'src/metrics_collector'
], function($, _, metricsJson, MetricsCollector) {
  describe("MetricsCollector", function() {
    var collector;
    beforeEach(function () {
      collector = MetricsCollector(metricsJson);
    });

    describe("registerListener", function() {
      it("registers listener to receive specific metrics on update", function() {
        var targetMetric = "rt/subtractor/bindcache/path/misses";
        var expectedDelta = 3;
        var data;
        var handler = function(resp) {
          data = resp.specific;
        }
        collector.registerListener(handler, function() { return [targetMetric];})
        var updateMetrics = _.clone(metricsJson);
        updateMetrics[targetMetric] += expectedDelta;
        collector.__update__(updateMetrics);

        expect(data.length).toEqual(1);
        expect(data[0].name).toEqual(targetMetric);
        expect(data[0].delta).toEqual(expectedDelta);
        expect(data[0].value).toEqual(metricsJson[targetMetric] + expectedDelta);
      });

      it("registers listener to receive full metrics response on update", function() {
        var data;
        var handler = function(resp) {
          data = resp.general;
        }
        collector.registerListener(handler, function() { return [];})
        collector.__update__(metricsJson);

        expect(typeof data).toEqual("object");
        expect(data["rt/subtractor/bindcache/path/misses"]).toEqual(1);
      });
    });

    describe("deregisterListener", function() {
      it("removes listener", function() {
        var wasCalled = false;
        var handler = function() {
          wasCalled = true;
        }
        collector.registerListener(handler, function() { return [];});
        collector.deregisterListener(handler);
        collector.__update__(metricsJson);
        expect(wasCalled).toEqual(false);
      });
    });
  });
});
