"use strict";
/**
  A module to consolidate our backend metric requests. Collects all metrics that
  we need.
*/
define(['jQuery'], function($) {

  var MetricsCollector = (function() {
    var updateUri = "admin/metrics.json?tree=1";
    var listeners = [];
    var clients = {};

    /**
      Register a listener to receive metric updates.
      handler: function called with incoming tree data
    */
    function registerListener(handler) {
      listeners.push({handler: handler});
    }

    function deregisterListener(handler) {
      _.remove(listeners, function(l) { return l.handler === handler; });
    }

    function calculateDeltas(resp, prevResp) {
      // modifies resp!
      _.each(resp, function(v, k) {
        if (k === "counter") {
          var prevValue = _.get(prevResp, k);
          var currentValue = _.get(resp, k);

          if (prevValue !== undefined && currentValue !== undefined) {
            _.set(resp, "delta", currentValue - prevValue);
          }
        } else {
          if (!_.isUndefined(resp[k]) && !_.isUndefined(prevResp[k])) {
            calculateDeltas(resp[k], prevResp[k]);
          }
        }
      });
    }

    function getAddedClients(resp) {
      var addedClients = {};
      _.each(resp.rt, function(routerData, router) {
        _.each(_.get(routerData, "dst.id"), function(clientData, client) {
          clients[router] = clients[router] || {};
          if(!clients[router][client]) {
            addedClients[router] = addedClients[router] || {};
            clients[router][client] = true;
            addedClients[router][client] = true;
          }
        });
      });
      return addedClients;
    }

    function onAddedClients(handler) {
      var wrapper = function(events, clients) {
        handler(clients);
      }
      $("body").on("addedClients", wrapper);
      return wrapper;
    }

    return function(initialMetrics) {
      var prevMetrics = initialMetrics;

      function update(resp) {
        calculateDeltas(resp, prevMetrics);

        var addedClients = getAddedClients(resp);
        if (!_.isEmpty(addedClients)) {
          $("body").trigger("addedClients", addedClients);
        }

        prevMetrics = resp;

        _.each(listeners, function(listener) {
          listener.handler(resp);
        });
      }

      return {
        start: function(interval, initialData) {
          _.each(initialData, function(data, rt) {
            _.each(_.get(data, 'clients'), function(client) {
              _.set(clients, [rt,client], true);
            });
          });

          $.get(updateUri).done(update);

          setInterval(function(){
            $.get(updateUri).done(update);
          }, interval);
        },
        registerListener: registerListener,
        deregisterListener: deregisterListener,
        /** Add event handler for new clients */
        onAddedClients: onAddedClients,
        __update__: update
      };
    };
  })();

  return MetricsCollector;
});

