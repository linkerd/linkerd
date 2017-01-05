"use strict";

define([
  'jQuery',
  'Handlebars',
  'src/delegator',
  'text!template/dentry.template',
  'text!template/delegatenode.template',
  'text!template/error_modal.template',
  'text!template/delegator.template'
], function(
  $, Handlebars,
  Delegator,
  dentryTemplate, nodeTemplate, modalTemplate, delegatorTemplate
) {
  return function() {
    var templates = {
      dentry: Handlebars.compile(dentryTemplate),
      node: Handlebars.compile(nodeTemplate),
      errorModal: Handlebars.compile(modalTemplate),
      delegator: Handlebars.compile(delegatorTemplate)
    }

    var dtab = JSON.parse($("#data").html());
    Delegator($(".delegator"), dtab.namespace, [], dtab.dtab, templates);
  }
});
