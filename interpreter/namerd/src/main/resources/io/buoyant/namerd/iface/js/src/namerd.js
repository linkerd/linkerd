"use strict";

define([
  'jQuery',
  'lodash',
  'Handlebars',
  'src/admin',
  'src/delegator',
  'text!template/dentry.template',
  'text!template/namerd_namespace.template',
], function(
  $, _, Handlebars,
  AdminHelpers,
  Delegator,
  dentryTemplate,
  namespaceTemplate
) {
  return function() {
    var data = JSON.parse($("#dtab-data").html());
    var dtabMap = _.groupBy(data, 'namespace');

    var template = {
      dentry: Handlebars.compile(dentryTemplate),
      namespace: Handlebars.compile(namespaceTemplate)
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
});
