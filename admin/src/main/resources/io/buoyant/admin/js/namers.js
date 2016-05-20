"use strict";

/**
 * Creates namer objects based off of a metrics payload.
 * Namer objects are of the form
 * {
 *    label: myLabel,
 *    prefix: "clnt/namer/myLabel/",
 *    metrics: {...}
 * }
 */

/* exported Namers */

var Namers = (function() {

  var clientRE = /^clnt\/namer\/(.+)\/requests$/;

  function mk(label) {
    return {
      label: label,
      prefix: "clnt/namer/" + label + "/",
      metrics: {requests: -1}
    };
  }

  function update(namers, metrics) {
    // first, check for new clients and add them
    _.each(metrics, function(metric, key) {
      var match = key.match(clientRE);
      if (match) {
        var name = match[1];
        namers[name] = namers[name] || mk(name);
      }
    });

    // then, attach metrics to each namer
    _.each(metrics, function(metric, key){
      var scope = findMatchingNamer(namers, key);

      if (scope) {
        var descoped = key.slice(scope.prefix.length);
        scope.metrics[descoped] = metric;
      }
    });
  }

  function findMatchingNamer(namers, key) {
    return _.find(namers, function(namer) { return key.indexOf(namer.prefix) === 0; });
  }

  /**
   * A constructor that initializes an object describing all of linker's namers,
   * with stats, from a raw key-val metrics blob.
   *
   * Returns an object that may be updated with additional data.
   */
  return function(metrics) {
    var namers = {};

    // clients and metrics are initialized now, and then may be updated later.
    update(namers, metrics);

    return {
      data: namers,

      /** Updates namer metrics (and clients) */
      update: function(metrics) { update(this.data, metrics); }
    };
  };
})();
