"use strict";

define([
  'jQuery',
  'src/utils',
  'template/compiled_templates'
  ], function($, Utils, templates) {
  /**
   * Process info for topline summary
   */
  var ProcInfo = (function() {

    var msToStr = new Utils.MsToStringConverter();
    var bytesToStr = new Utils.BytesToStringConverter();
    var template = templates.process_info;

    var stats = [
      { description: "version", dataKey: "" },
      { description: "uptime", dataKey: ["uptime", "gauge"],  value: "0s" },
      { description: "thread count", dataKey: ["thread", "count", "gauge"], value: "0" },
      { description: "memory used", dataKey: ["mem", "current", "used", "gauge"], value: "0MB" },
      { description: "gc", dataKey: ["gc", "msec", "gauge"], value: "1ms" }
    ];

    function pretty(name, value) {
      switch (name) {
        case "uptime": return msToStr.convert(value);
        case "memory used": return bytesToStr.convert(value);
        case "gc": return msToStr.convert(value);
        default: return value;
      }
    }

    function render($root, data) {
      console.log(data);
      var templateData = _.map(stats, function(stat) {
        if (stat.dataKey) {
          var value = pretty(stat.description, _.get(data, stat.dataKey));
          return _.merge(stat, { value: value });
        } else {
          return stat;
        }
      });
      $root.html(template({stats: templateData}))
    }

    return function(metricsCollector, $root, buildVersion) {
      stats[0].value = buildVersion;

      if (metricsCollector) {
        metricsCollector.registerListener(
          function(data){ render($root, data.treeSpecific.jvm); },
          function() {
            return _.map(stats, "dataKey");
          });
      }

      return {};
    };
  })();

  return ProcInfo;
});
