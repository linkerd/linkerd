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

  return function(metricsCollector, selectedRouter, routers, templates, $parentContainer) {
    var routerContainerEls = initializeRouterContainers(selectedRouter, routers, $parentContainer, templates.container);

    var routerSummaries = [];
    var routerServers = [];

    _.each(routerContainerEls, function(container, router) {
      var $summaryEl = $(container.find(".summary")[0]);
      var $serverEl = $(container.find(".server")[0]);

      routerSummaries.push(RouterSummary(metricsCollector, routers, templates.summary, $summaryEl, router));
      routerServers.push(RouterServer(routers, $serverEl, router));
    });

    return {};
  };
})();
