var RouterController = (function () {
  // Contains all the router components, e.g. summaries, graphs, etc

  var colors = {
    yellows: {
      light: "#FFE7B3",
      tint: "#FAD78A",
      yellow: "#ED9E64",
      shade: "#D85B00",
      dark: "#B84D00"
    },
    greys : {
      light: "#F2F2F2",
      tint: '#C9C9C9',
      grey: "#878787",
      shade: "#424242",
      dark: "#2B2B2B"
    },
    blues: {
      light: "#D1E2FB",
      tint: "#A4C4F1",
      blue: "#709DDD",
      shade: "#4076C4",
      dark: "#163F79",
      night: "#0F2A50"
    },
    purples: {
      light: "#E1D1F6",
      tint: "#CAA2EA",
      purple: "#9B4AD8",
      shade: "#6A18A4",
      dark: "#430880",
      night: "#2A084C"
    },
    greens: {
      light: "#D1F6E8",
      tint: "#A2EACF",
      green: "#4AD8AC",
      shade: "#18A478",
      dark: "#08805B"
    },
    reds: {
      light: "#F6D1D1",
      tint: "#EAA2A2",
      red: "#D84A4A",
      shade: "#A41818",
      dark: "#800808"
    }
  }

  //TODO: assign colorOrder colors to clients by client name
  var colorOrder = [
    colors.purples.purple,
    colors.yellows.yellow,
    colors.blues.blue,
    colors.greens.green,
    colors.reds.red,
    colors.purples.shade,
    colors.yellows.shade,
    colors.blues.shade,
    colors.greens.shade,
    colors.reds.shade
  ];

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

    _.each(routerContainerEls, function(container, router) {
      var $summaryEl = $(container.find(".summary")[0]);
      var $serverEl = $(container.find(".server")[0]);

      RouterSummary(metricsCollector, routers, templates.summary, $summaryEl, router);
      CombinedClientGraph(metricsCollector, router, container.find(".router-graph"), colorOrder);
      RouterServer(routers, $serverEl, router);
    });

    return {};
  };
})();
