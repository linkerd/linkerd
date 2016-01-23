"use strict";

$(function() {
  // highlight current page in navbar
  var path = window.location.pathname;
  $('a[href="' + path + '"]').parent().addClass("current");
});
