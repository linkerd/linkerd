/* globals RouterClients, RouterServers, RouterSummary */
/* exported RouterController */
var RouterController = (function () {
  // Contains all the router components, e.g. summaries, graphs, etc

  var colors = {
    yellows: {
      light: "#FFE7B3",
      tint: "#FAD78A",
      neutral: "#ED9E64",
      shade: "#D85B00",
      dark: "#B84D00"
    },
    greys : {
      light: "#F2F2F2",
      tint: '#C9C9C9',
      neutral: "#878787",
      shade: "#424242",
      dark: "#2B2B2B"
    },
    blues: {
      light: "#D1E2FB",
      tint: "#A4C4F1",
      neutral: "#709DDD",
      shade: "#4076C4",
      dark: "#163F79",
      night: "#0F2A50"
    },
    purples: {
      light: "#E1D1F6",
      tint: "#CAA2EA",
      neutral: "#9B4AD8",
      shade: "#6A18A4",
      dark: "#430880",
      night: "#2A084C"
    },
    greens: {
      light: "#D1F6E8",
      tint: "#A2EACF",
      neutral: "#4AD8AC",
      shade: "#18A478",
      dark: "#08805B"
    },
    reds: {
      light: "#F6D1D1",
      tint: "#EAA2A2",
      neutral: "#D84A4A",
      shade: "#A41818",
      dark: "#800808"
    }
  }

  var baseColorOrder = [
    "purples.neutral",
    "yellows.neutral",
    "blues.neutral",
    "greens.neutral",
    "reds.neutral",
    "purples.shade",
    "yellows.shade",
    "blues.shade",
    "greens.shade",
    "reds.shade"
  ];

  var colorOrder = _.map(baseColorOrder, function(colorName) {
    return {
      color: _.property(colorName)(colors),
      colorFamily: colors[colorName.split(".")[0]]
    }
  });

  function getSelectedRouterData(selectedRouter, routers) {
    var routerData = {};

    if (!selectedRouter || !routers.data[selectedRouter]) {
      routerData = routers.data;
    } else {
      routerData[selectedRouter] = routers.data[selectedRouter];
    }
    return routerData;
  }

  function initializeRouterContainers(selectedRouter, routers, $parentContainer, template) {
    var routerData = getSelectedRouterData(selectedRouter, routers);
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
      var $serversEl = $(container.find(".servers")[0]);
      var $clientsEl = $(container.find(".clients")[0]);

      RouterSummary(metricsCollector, templates.summary, $summaryEl, router);
      RouterServers(metricsCollector, routers, $serversEl, router, templates.server, templates.serverMetric, templates.serverContainer);
      RouterClients(metricsCollector, routers, $clientsEl, router , templates.client, templates.metric, templates.clientContainer, colorOrder);
    });

    return {};
  };
})();
