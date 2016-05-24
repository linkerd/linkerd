"use strict";
var SINGLE_ROUTER_PAGES_ONLY = SINGLE_ROUTER_PAGES_ONLY || false;

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
  $.get("/config.json")
).done(function(templateRsp, routersRsp) {
  var template = Handlebars.compile(templateRsp[0]);
  var routers = routersRsp[0].routers;

  //Not every page supports a mult-router view!
  if(!SINGLE_ROUTER_PAGES_ONLY) {
    routers.unshift({label: "all"});
  }

  var routerHtml = routers.map(template);

  $(".dropdown-menu").html(routerHtml.join(""));

  $(".router-menu-option").click(function() {
    selectRouter($(this).text());
  });
});

function selectRouter(label) {
  if (label === "all") {
    unselectRouter();
    return;
  }

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

function unselectRouter() {
  var re = /router=(.+)(?:&|$)/
  window.location.search = window.location.search.replace(re, "");
}

/* exported getSelectedRouter */
function getSelectedRouter() {
  return $(".dropdown-toggle .router-label").text();
}
