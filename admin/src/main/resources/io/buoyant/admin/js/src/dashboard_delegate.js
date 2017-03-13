"use strict";

define([
  'jQuery',
  'src/delegator'
], function(
  $,
  Delegator
) {
  return function() {
    var dtab = JSON.parse($("#data").html());
    Delegator($(".delegator"), dtab.namespace, [], dtab.dtab);
  }
});
