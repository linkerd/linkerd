"use strict";

define([
  'jQuery',
  'Handlebars',
  'src/delegator'
  'text!template/dentry.template',
  'text!template/delegatenode.template',
  'text!template/error_modal.template',
  'text!template/delegator.template'
], function($, Handlebars, Delegator,
  dentryRsp, nodeRsp, modalRsp, delegatorRsp
) {
  return function() {
    var templates = {
      dentry: Handlebars.compile(dentryRsp[0]),
      node: Handlebars.compile(nodeRsp[0]),
      errorModal: Handlebars.compile(modalRsp[0]),
      delegator: Handlebars.compile(delegatorRsp[0])
    }

    var dtab = JSON.parse($("#data").html());
    Delegator($(".delegator"), dtab.namespace, [], dtab.dtab, templates);
  }
});
