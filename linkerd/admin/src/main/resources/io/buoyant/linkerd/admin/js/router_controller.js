var RouterController = (function () {
  // Contains all the router components, e.g. summaries, graphs, etc

  function initializeRouterContainers(selectedRouter, routers, $parentContainer, template) {
    var routerData = !selectedRouter ? routers.data : { router: routers.data[selectedRouter] };
    var containers = template({ routers: _.keys(routerData) });
    $parentContainer.html(containers);

    var routerContainers = {};
    $(".router").each(function (i,el) {
      var $el = $(el);
      routerContainers[$el.data("router")] = $el;
    });

    return routerContainers;
  }

  function processResponses(rawData) {
    // group raw data by router, extract metric names
    return _.chain(rawData)
      .filter(isRouter)
      .groupBy(getRouterName)
      .mapValues(function(data, router) {
        var result = { router: router };
        _.each(data, function(datum) {
          var keyParts = datum.name.split("/");
          var metric = keyParts[keyParts.length - 1];
          result[metric] = datum;
        });
        return result;
      })
      .value();
  }

  function isRouter(datum) {
    return datum.name.indexOf("rt/") === 0;
  }

  function getRouterName(datum) {
    return datum.name.split("/")[1];
  }

  return function(selectedRouter, routers, templates, $parentContainer) {
    var routerContainerEls = initializeRouterContainers(selectedRouter, routers, $parentContainer, templates.container);

    var routerSummaries = [];
    var routerServers = [];

    _.each(routerContainerEls, function(container, router) {
      var $summaryEl = $(container.find(".summary")[0]);
      var $serverEl = $(container.find(".server")[0]);

      routerSummaries.push(RouterSummary(routers, templates.summary, $summaryEl, router));
      routerServers.push(RouterServer(routers, $serverEl, router));
    });

    return {
      onMetricsUpdate: function(data) {
        routers.update(data.general);
        var transformedData = processResponses(data.specific);

        _.each(routerSummaries, function(routerSummary) {
          var routerData = transformedData[routerSummary.getRouterName()];
          if (!_.isEmpty(routerData)) routerSummary.onMetricsUpdate(routerData);
        });

        _.each(routerServers, function(routerServer) {
          routerServer.onMetricsUpdate();
        });
      },
      desiredMetrics: function() {
        var metrics = _.chain(_.concat(routerSummaries, routerServers))
          .map(function(ea) { return ea.desiredMetrics() })
          .flatten().value();

        return metrics;
      }
    };
  };
})();
