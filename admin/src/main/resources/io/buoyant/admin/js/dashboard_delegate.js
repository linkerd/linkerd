"use strict";

/* globals Delegator */

$.when(
  $.get("/files/template/dentry.template"),
  $.get("/files/template/delegatenode.template"),
  $.get("/files/template/error_modal.template"),
  $.get("/files/template/delegator.template")
).done(function(dentryRsp, nodeRsp, modalRsp, delegatorRsp){
  var templates = {
    dentry: Handlebars.compile(dentryRsp[0]),
    node: Handlebars.compile(nodeRsp[0]),
    errorModal: Handlebars.compile(modalRsp[0]),
    delegator: Handlebars.compile(delegatorRsp[0])
  }
  var dtab = JSON.parse($("#data").html());
  Delegator($(".delegator"), dtab.namespace, [], dtab.dtab, templates);

});
