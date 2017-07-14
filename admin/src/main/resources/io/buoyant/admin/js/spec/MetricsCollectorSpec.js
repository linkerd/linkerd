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
        var targetMetric = ["rt", "subtractor", "bindcache", "path", "misses", "counter"];
        var expectedDelta = 3;
        var data;
        var handler = function(resp) {
          data = resp;
        }
        collector.registerListener("testListener", handler);
        var updateMetrics = _.cloneDeep(metricsJson);
        _.set(updateMetrics, targetMetric, _.get(metricsJson, targetMetric) + expectedDelta);
        collector.__update__(updateMetrics);

        var result = _.get(data, ["rt", "subtractor", "bindcache", "path", "misses"]);

        expect(result.delta).toEqual(expectedDelta);
        expect(result.counter).toEqual(_.get(metricsJson, targetMetric) + expectedDelta);
      });

      it("registers listener to receive full metrics response on update", function() {
        var data;
        var handler = function(resp) {
          data = resp;
        }
        collector.registerListener("testListener", handler);
        collector.__update__(metricsJson);

        expect(typeof data).toEqual("object");
        expect(_.get(data, ["rt", "subtractor", "bindcache", "path", "misses", "counter"])).toEqual(7);
      });
    });

    describe("deregisterListener", function() {
      it("removes specified listener", function() {
        var dogWasCalled = false;
        var catWasCalled = false;

        var dogHandler = function() { dogWasCalled = true; }
        var catHandler = function() { catWasCalled = true; }
        collector.registerListener("dogListener", dogHandler);
        collector.registerListener("catListener", catHandler);
        collector.deregisterListener("dogListener");

        collector.__update__(metricsJson);

        expect(dogWasCalled).toEqual(false);
        expect(catWasCalled).toEqual(true);
      });
    });
  });
});
