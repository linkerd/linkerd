"use strict";

define(['lodash'], function(_) {
  var latencyMetricToColorShade = {
    "max": "light",
    "p9990": "tint",
    "p99": "neutral",
    "p95": "shade",
    "p50": "dark"
  }
  var latencyKeys = _.keys(latencyMetricToColorShade);

  function createLatencyLegend(colorLookup) {
    return _.mapValues(latencyMetricToColorShade, function(shade) {
      return colorLookup[shade];
    });
  }

  function getLatencyData(latencyData, latencyLegend) {
    return _.map(latencyKeys, function(key) {
      return {
        latencyLabel: key,
        latencyValue: _.get(latencyData, "stat." + key),
        latencyColor: latencyLegend[key]
      };
    });
  }

  return {
    createLatencyLegend: createLatencyLegend,
    getLatencyData: getLatencyData
  };
});
