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
      { description: "uptime", dataKey: "jvm/uptime",  value: "0s" },
      { description: "thread count", dataKey: "jvm/thread/count", value: "0" },
      { description: "memory used", dataKey: "jvm/mem/current/used", value: "0MB" },
      { description: "gc", dataKey: "jvm/gc/msec", value: "1ms" }
    ];

    function pretty(name, value) {
      switch (name) {
        case "jvm/uptime": return msToStr.convert(value);
        case "jvm/mem/current/used": return bytesToStr.convert(value);
        case "jvm/gc/msec": return msToStr.convert(value);
        default: return value;
      }
    }

    function render($root, data) {
      var templateData = _.map(stats, function(stat) {
        if (stat.dataKey) {
          var obj = _.find(data, ["name", stat.dataKey]);
          var value = pretty(obj.name, obj.value);
          return _.merge(stat, {value: value});
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
          function(data){ render($root, data.specific); },
          function() {
            return _.map(stats, "dataKey");
          });
      }

      return {};
    };
  })();

  return ProcInfo;
});
