"use strict";

define([
  'jQuery',
  'handlebars.runtime',
  'src/colors',
  'src/router_client',
  'src/combined_client_graph',
  'template/compiled_templates'
], function(
  $,
  Handlebars,
  Colors,
  RouterClient,
  CombinedClientGraph,
  templates
) {
  var RouterClients = (function() {
    var clientContainerTemplate = templates.router_client_container;
    var rateMetricPartial = templates["rate_metric.partial"];
    var EXPAND_CLIENT_THRESHOLD = 6;
    var TRANSFORMER_RE = /(\/%\/[^\$#]*)?(\/[\$#]\/.*)/;
    var activeClients = {};

    /*
      RouterClients can be in 1 of 3 states: COLLAPSED, EXPANDED, or CUSTOMIZED.

      COLLAPSED:
        Occurs if there are more than THRESHOLD clients on pageload.
        Any added clients are collapsed. Dropping below 6 clients transitions to EXPANDED.
        User opening any clients transitions to CUSTOMIZED.

      EXPANDED:
        Occurs if there are less than THRESHOLD clients on pageload.
        Any added clients are expanded.
        Adding more than 6 clients transitions to COLLAPSED.
        User closing any clients transitions to CUSTOMIZED.

      CUSTOMIZED:
        Any added clients are expanded.
        There is no transition away from this state.
    */
    var expandStates = { expanded: "EXPANDED", collapsed: "COLLAPSED", custom: "CUSTOM" };
    var clientExpandState = {};

    function assignColorsToClients(colors, clients) {
      return _.reduce(clients, function(clientMapping, client, idx) {
        clientMapping[client] = colors[idx % colors.length];
        return clientMapping;
      }, {});
    }

    function shouldExpandClient(routerName, initialClients) {
      // if there are many clients, collapse them by default to improve page perfomance
      if (clientExpandState[routerName] === expandStates.custom) {
        return true;
      } else if (initialClients) {
        return initialClients < EXPAND_CLIENT_THRESHOLD;
      } else {
        return getNumActiveClients(routerName) < EXPAND_CLIENT_THRESHOLD;
      }
    }

    function getNumActiveClients(routerName) {
      var countByActive = _.countBy(activeClients[routerName], ["isExpired", false]);
      return countByActive.true || 0;
    }

    function toggleAllClients(routerName, shouldExpand) {
      // also resets the customized state
      clientExpandState[routerName] = shouldExpand ? expandStates.expanded : expandStates.collapsed;
      _.each(activeClients[routerName], function(clientData) {
        clientData.toggleClientDisplay(shouldExpand);
      });
    }

    return function (metricsCollector, initialData, $clientEl, $combinedClientGraphEl, routerName) {
      Handlebars.registerPartial('rateMetricPartial', rateMetricPartial);

      var clients = _.sortBy(initialData[routerName].clients);
      var colorList = Colors;
      var clientToColor = assignColorsToClients(colorList, clients);
      var combinedClientGraph = CombinedClientGraph(metricsCollector, initialData, routerName, $combinedClientGraphEl, clientToColor);

      activeClients[routerName] = {};
      var routerClients = _.map(clients, function(client) {
        return initializeClient(client);
      });

      routerClients = _.compact(routerClients);

      if (routerClients.length == 0) {
        $clientEl.addClass("hidden");
      } else {
        clientExpandState[routerName] = routerClients.length > EXPAND_CLIENT_THRESHOLD ? expandStates.collapsed : expandStates.expanded;
      }

      var $expandAll = $clientEl.find(".expand-all");
      var $collapseAll = $clientEl.find(".collapse-all");

      $expandAll.click(function() {
        toggleAllClients(routerName, true);
      });
      $collapseAll.click(function() {
        toggleAllClients(routerName, false);
      });

      metricsCollector.onAddedClients(addClients);

      function initializeClient(client) {
        $clientEl.removeClass("hidden");
        var colorsForClient = clientToColor[client];
        var match = ('/' + client).match(TRANSFORMER_RE);

        //client names that don't conform to the regex are not displayed
        if (!match) return null;

        var $container = $(clientContainerTemplate({
          clientColor: colorsForClient.color,
          prefix: match[1],
          client: match[2]
        })).appendTo($clientEl);
        if (match[1]) {
          var $transformerPrefix = $container.find(".transformer-prefix");
          var $clientSuffix = $container.find(".client-suffix");

          $container.find(".client-id")
            .click(function() {
              $transformerPrefix.toggle("slow", function() {
                $clientSuffix.toggleClass("is-first", !$transformerPrefix.is(":visible"));
              });
            });
        }

        var shouldExpand = shouldExpandClient(routerName, initialData[routerName].clients.length);
        if(!clientExpandState[routerName] === expandStates.custom) {
          // switch over to expanding/collapsing new clients
          clientExpandState[routerName] = shouldExpand ? expandStates.expanded : expandStates.collapsed;
        }

        var routerClient = RouterClient(metricsCollector, client, $container, routerName, colorsForClient, shouldExpand, combinedClientGraph);
        activeClients[routerName][client] = routerClient;

        $container.on("expire-client", function() {
          $container.addClass("hidden");

          if (getNumActiveClients(routerName) === 0) {
            $clientEl.addClass("hidden");
          }
        });
        $container.on("revive-client", function() {
          routerClient.toggleClientDisplay(clientExpandState[routerName] !== expandStates.collapsed);
          $clientEl.removeClass("hidden");
          $container.removeClass("hidden");
        });
        $container.on("expand-custom", function() {
          clientExpandState[routerName] = expandStates.custom;
        });

        return routerClient;
      }

      function addClients(addedClients) {
        // filter new clients
        var filteredClients = _.keys(addedClients[routerName]);

        // combine with existing clients
        clients = _(clients).concat(filteredClients).uniq().value();

        // reassign colors
        clientToColor = assignColorsToClients(colorList, clients);

        // pass new colors to combined request graph, add new clients to graph
        combinedClientGraph.updateColors(clientToColor);
        combinedClientGraph.addClients(filteredClients);

        // add new clients to dom
        _.each(filteredClients, function(clientForRouter) {
          var routerClient = initializeClient(clientForRouter);
          if (routerClient) {
            routerClients.push(routerClient);
          }
        });
      }
    }
  })();

  return RouterClients;
});
