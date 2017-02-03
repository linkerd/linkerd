"use strict";

define([
  'jQuery',
  'template/compiled_templates',
  'bootstrap'
], function($, templates) {
  function initialize(removeRoutersAllOption) {
    // highlight current page in navbar
    var path = window.location.pathname;
    $('a[href="' + path + '"]').parent().addClass("active");

    var matches = window.location.search.match(/router=(.+)(?:&|$)/)
    if (matches && matches.length > 1) {
      var label = decodeURIComponent(matches[1]);
      $(".dropdown-toggle .router-label").text(label)
    }

    return $.get("config.json").done(function(routersRsp) {
      var template = templates.router_option;
      var routers = routersRsp.routers;

      if (!matches && routers.length > 0) {
        selectRouter(routers[0].label || routers[0].protocol);
      }

      //Not every page supports a mult-router view!
      if(!removeRoutersAllOption || routers.length === 0) {
        routers.push({label: "all"});
      }
      var routerHtml = routers.map(template);

      $(".dropdown-menu").html(routerHtml.join(""));

      $(".router-menu-option").click(function() {
        selectRouter($(this).text());
      });

      return routersRsp;
    });
  }

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

  function unselectRouter() {
    var re = /router=(.+)(?:&|$)/
    window.location.search = window.location.search.replace(re, "");
  }

  function getSelectedRouter() {
    return $(".dropdown-toggle .router-label").text();
  }

  return {
    initialize: initialize,
    selectRouter: selectRouter,
    unselectRouter: unselectRouter,
    getSelectedRouter: getSelectedRouter
  };
});
