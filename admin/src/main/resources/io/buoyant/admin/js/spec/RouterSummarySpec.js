"use strict";

define([
  'jQuery',
  'lodash',
  'src/router_summary',
  'src/metrics_collector',
  'spec/fixtures/metrics',
  'template/compiled_templates'
], function($, _, RouterSummary, MetricsCollector, metricsJson, templates) {
  describe("RouterSummary", function() {
    var $container;
    var $summaryEl;
    var $routerStatsEl;

    var updatedMetrics = _.merge({}, metricsJson, {
      rt: {
        multiplier: {
          "service": {
            "svc": {
              "retries": {
                "total": {
                  "counter": 481
                }
              }
            }
          },
          client: {
            "$/inet/127.1/9030": {
              "retries": {
                "requeues": {
                  "counter": 100
                }
              }
            }
          },
          server: {
            "0.0.0.0/4116": {
              "requests": {
                counter: 1234
              }
            }
          }
        }
      }
    });

    function extractRenderedValue($parent, dataKey) {
      return $($parent.find("div[data-key='" + dataKey + "']")[0]).text().trim();
    }

    beforeEach(function () {
      $container = $("<div />");
      var containers = templates.router_container({ routers: ["multiplier"] });
      $container.html(containers);

      $summaryEl = $($container.find(".summary")[0]);
      $routerStatsEl = $($container.find(".router-stats")[0]);
    });

    afterEach(function () {
      $summaryEl.remove();
      $summaryEl = null;
      $routerStatsEl.remove();
      $routerStatsEl = null;
      $container.remove();
      $container = null;
    });

    it("updates summary stats correctly", function() {
      var collector = MetricsCollector(metricsJson);
      RouterSummary(collector, $summaryEl, $routerStatsEl, "multiplier", null);
      var summaryStats = $summaryEl.find(".router-summary-stat");

      expect(summaryStats.length).toBe(5);
      expect(extractRenderedValue(summaryStats, "requests")).toBe("0");
      expect(extractRenderedValue(summaryStats, "retries")).toBe("0");

      collector.__update__(updatedMetrics);

      summaryStats = $summaryEl.find(".router-summary-stat");

      expect(extractRenderedValue(summaryStats, "requests")).toBe("853");
      expect(extractRenderedValue(summaryStats, "retries")).toBe("581"); // 481 + 100
    });
  });
});
