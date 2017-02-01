"use strict";

define([
  'jQuery',
  'Handlebars',
  'text!template/usage.template',
], function(
  $, Handlebars,
  usageTemplate
) {
  return function() {
    var template = Handlebars.compile(usageTemplate)
    var $usageContainer = $(".usage-content");

    $.get("admin/metrics/usage").done(function(usageJson) {
      var view = {usageJson: JSON.stringify(usageJson, null, '  ')};
      var $templateContainer = $(template(view));
      $templateContainer.appendTo($usageContainer);
    });
  }
});
