"use strict";

$(function() {
  // highlight current page in navbar
  var path = window.location.pathname;
  $('a[href="' + path + '"]').parent().addClass("current");

  var matches = window.location.search.match(/router=(.+)(?:&|$)/)
  if (matches && matches.length > 1) {
    var label = decodeURIComponent(matches[1]);
    $(".dropdown-toggle .router-label").text(label)
  }
});

$.when(
  $.get("/files/template/router_option.template"),
  $.get("/routers.json")
).done(function(templateRsp, routers) {
  var template = Handlebars.compile(templateRsp[0])
  var routerHtml = routers[0].map(template);

  $(".dropdown-menu").html(routerHtml.join(""));

  $(".router-menu-option").click(function() {
    selectRouter($(this).text());
  });
});

function selectRouter(label) {
  var uriComponent = "router=" + encodeURIComponent(label);
  var re = /router=(.+)(?:&|$)/
  if (window.location.search == "") {
    window.location.search = uriComponent;
  } else if (window.location.search.match(re)) {
    window.location.search = window.location.search.replace(re, uriComponent);
  } else {
    window.location.search = window.location.search + "&" + uriComponent;
  }
}
