"use strict";

define([
  'jQuery',
  'src/admin',
  'src/delegator'
], function(
  $,
  AdminHelpers,
  Delegator
) {
  return function() {
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

      Delegator($(".delegator"), selectedRouter, dtab, dtabBase);
    }
  }
});
