"use strict";

define([
  'jQuery',
  'template/compiled_templates',
  'bootstrap'
], function($, templates) {
  function initialize(removeRoutersAllOption) {

    var matches = window.location.search.match(/router=(.+)(?:&|$)/)
    if (matches && matches.length > 1) {
      var label = decodeURIComponent(matches[1]);
      $(".dropdown-toggle .router-label").text(label)
    }

    return $.get("config.json").done(function(routersRsp) {
      var routers = routersRsp.routers || [];
      addAllRoutersLabel(routers, removeRoutersAllOption);

        // selectRouter appends ?router=all to the address bar URL which causes an additional page
        // reload. We need a way to prevent this when running Namerd since Namerd doesn't use
        // routers. We use routersRps.storage as a way to differentiate between a Namerd admin page
        // or a Linkerd admin page. If routersRsp.storage doesn't exist in the config.json, we know
        // that we are viewing a Namerd admin page so don't call selectRouter.
        if(window.location.pathname === "/" && !matches && routers.length > 0 && !routersRsp.storage) {
          selectRouter(routers[0].label || routers[0].protocol);
      }
      return routersRsp;
    });
  }

  function addAllRoutersLabel(routers, removeRoutersAllOption) {
    var template = templates.router_option;

    //Not every page supports a mult-router view!
    if(!removeRoutersAllOption || routers.length === 0) {
      routers.push({label: "all"});
    }
    var routerHtml = routers.map(template);

    $(".dropdown-menu").html(routerHtml.join(""));

    $(".router-menu-option").click(function() {
      selectRouter($(this).text());
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
