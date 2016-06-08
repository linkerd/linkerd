"use strict";

/* globals Delegator, getSelectedRouter, selectRouter */
/* exported SINGLE_ROUTER_PAGES_ONLY */
var templates = {};
var SINGLE_ROUTER_PAGES_ONLY = true;

$.when(
  $.get("/files/template/dentry.template"),
  $.get("/files/template/delegatenode.template"),
  $.get("/files/template/error_modal.template"),
  $.get("/files/template/delegator.template")
).done(function(dentryRsp, nodeRsp, modalRsp, delegatorRsp){
  templates.dentry = Handlebars.compile(dentryRsp[0]);
  templates.node = Handlebars.compile(nodeRsp[0]);
  templates.errorModal = Handlebars.compile(modalRsp[0]);
  templates.delegator = Handlebars.compile(delegatorRsp[0]);

  var dtabMap = JSON.parse($("#dtab-data").html());
  var dtabBaseMap = JSON.parse($("#dtab-base-data").html());

  var selectedRouter = getSelectedRouter();
  var dtab = dtabMap[selectedRouter];
  var dtabBase = dtabBaseMap[selectedRouter];

  if (!dtab) {
    var defaultRouter = $(".router-menu-option:first").text();
    if (dtabMap[defaultRouter]) {
      selectRouter(defaultRouter);
    } else {
      console.warn("undefined router:", selectedRouter);
    }
  } else {
    $(".router-label-title").text("Router \"" + selectedRouter + "\"");

    Delegator($(".delegator"), selectedRouter, dtab, dtabBase, templates);
  }
});
