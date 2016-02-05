"use strict";

var templates = {};
var SINGLE_ROUTER_PAGES_ONLY = true;

$.when(
  $.get("/files/template/dentry.template"),
  $.get("/files/template/delegatenode.template")
).done(function(dentryRsp, nodeRsp){
  templates.dentry = Handlebars.compile(dentryRsp[0]);
  templates.node = Handlebars.compile(nodeRsp[0]);
  var dtabMap = JSON.parse($("#data").html());

  var selectedRouter = getSelectedRouter();
  var dtab = dtabMap[selectedRouter];

  if (!dtab) {
    var defaultRouter = $(".router-menu-option:first").text();
    if (dtabMap[defaultRouter]) {
      selectRouter(defaultRouter);
    } else {
      console.warn("undefined router:", selectedRouter);
    }
  } else {
    $(".router-label-title").text("Router \"" + selectedRouter + "\"");

    var dtabViewer = new DtabViewer(dtab, templates.dentry);
    $(function(){
      $('.go').click(function(e){
        e.preventDefault();
        var path = $('.path input').val();
        $.get("delegator.json?" + $.param({ n: path, d: dtabViewer.dtabStr() }), renderAll);
      });
    });
  }
});

$(function() {
  // delegate when enter is pressed and input is focused
  $(document).keypress(function(e) {
    if(e.which == 13 && $('#path-input').is(":focus")) {
      $('.go').click();
    }
  });

  $('#path-input').focus();
});

function renderAll(resp) {
  $('.result').html(renderNode(resp));
}

function renderNode(obj){
  switch(obj.type) {
    case "delegate": obj.isDelegate = true; obj.child = renderNode(obj.delegate); break;
    case "alt": obj.isAlt = true; obj.child = obj.alt.map(function(e,i){ return renderNode(e); }).join(""); break;
    case "neg": obj.isNeg = true; obj.break;
    case "fail": obj.isFail = true; break;
    case "leaf": obj.isLeaf = true; obj.child = renderNode(obj.bound); break;
  }
  return templates.node(obj);
}
