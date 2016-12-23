"use strict";

define([
  'jQuery',
  'Handlebars',
  'src/admin',
  'src/delegator',
  'text!template/dentry.template',
  'text!template/delegatenode.template',
  'text!template/error_modal.template',
  'text!template/delegator.template'
], function(
  $, Handlebars,
  AdminHelpers,
  Delegator,
  dentryTemplate, nodeTemplate, modalTemplate, delegatorTemplate
) {
  return function() {
    var templates = {};

    templates.dentry = Handlebars.compile(dentryTemplate);
    templates.node = Handlebars.compile(nodeTemplate);
    templates.errorModal = Handlebars.compile(modalTemplate);
    templates.delegator = Handlebars.compile(delegatorTemplate);

    var dtabMap = JSON.parse($("#dtab-data").html());
    var dtabBaseMap = JSON.parse($("#dtab-base-data").html());

    var selectedRouter = AdminHelpers.getSelectedRouter();
    var dtab = dtabMap[selectedRouter];
    var dtabBase = dtabBaseMap[selectedRouter];

    if (!dtab) {
      var defaultRouter = $(".router-menu-option:first").text();
      if (dtabMap[defaultRouter]) {
        AdminHelpers.selectRouter(defaultRouter);
      } else {
        console.warn("undefined router:", selectedRouter);
      }
    } else {
      $(".router-label-title").text("Router \"" + selectedRouter + "\"");

      Delegator($(".delegator"), selectedRouter, dtab, dtabBase, templates);
    }
  }
});
