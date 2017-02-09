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
  templates,
  namerdTmplRsp
) {
  function renderDtabNamespaces() {
    var data = JSON.parse($("#dtab-data").html());
    var dtabMap = _.groupBy(data, 'namespace');

    var template = {
      dentry: templates.dentry,
      namespace: templates.namerd_namespace
    }

    var $namespacesContainer = $("#dtab-namespaces");

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
    }
  }

  function getStat(metrics, stat) {
    var prefix = 'rt/interpreter/io.buoyant.namerd.iface.NamerdInterpreterConfig/';
    return metrics[prefix + stat];
  }
  function fetchNamerdStats() {
    $.get("admin/metrics.json", renderNamerdStats);
  }
  function renderNamerdStats(data) {
    var namerdTmpl = templates.namerd_stats;
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
    }
    var html = templates.namerd_stats(dataToRender);
    $statsContainer.html(html);
  }

  return function() {
    Handlebars.registerPartial('metricPartial', templates["metric.partial"]);

    renderDtabNamespaces();
    fetchNamerdStats();
  }
});
