"use strict";

define([
  'jQuery',
  'lodash',
  'handlebars.runtime',
  'src/admin',
  'src/delegator',
  'template/compiled_templates'
], function(
  $, _, Handlebars,
  AdminHelpers,
  Delegator,
  templates
) {
  var limitWidth;

  function renderDtabNamespaces() {
    var data = JSON.parse($("#dtab-data").html());
    var dtabMap = _.groupBy(data, 'namespace');

    var template = {
      dentry: templates.dentry,
      namespace: templates.namerd_namespace
    }

    var $namespacesContainer = $("#dtab-namespaces");
    limitWidth = $namespacesContainer.width() / 2 - 60;

    for (var label in dtabMap) {
      var routers = dtabMap[label];
      var routerLabels = routers.map(function(r) {return r.routerLabel;});
      var templateView = {namespace: label, routers: routerLabels};
      var $namespaceContainer = $(template.namespace(templateView));
      var dtab = routers[0].dtab;
      if (dtab.length) {
        $namespaceContainer.append(dtab.map(function(e) {
          return template.dentry(e);
        }).join(""));
      } else {
        $namespaceContainer.append("<p>unable to fetch dtab</p>")
      }
      $namespaceContainer.appendTo($namespacesContainer);
      resizeWidths($namespaceContainer);
    }
  }

  function resizeWidths($namespaceContainer) {
    var maxWidth = 0;
    var dentries = $namespaceContainer.find(".dentry-part");
    $(dentries).map(function(_i, dentry) {
      var w = $(dentry).width();
      maxWidth = w > maxWidth ? w : maxWidth;
    })
    $(dentries).width(Math.min(maxWidth, limitWidth));
  }

  function getStat(metrics, stat) {
    var prefix = 'rt/interpreter/io.buoyant.namerd.iface.NamerdInterpreterConfig/';
    return metrics[prefix + stat];
  }

  function renderNamerdStats(data) {
    var $statsContainer = $("#namerd-stats");
    var dataToRender = {
      connections: {
        description: "Connections",
        value: getStat(data, "connections")
      },
      bindcache: {
        description: "Bindcache size",
        value: getStat(data, "bindcache.size")
      },
      addrcache: {
        description: "Addrcache size",
        value: getStat(data, "addrcache.size")
      }
    };
    var html = templates.namerd_stats(dataToRender);
    $statsContainer.html(html);
  }

  return function() {
    Handlebars.registerPartial('metricPartial', templates["metric.partial"]);

    renderDtabNamespaces();
    $.get("admin/metrics.json", renderNamerdStats);
  }
});
