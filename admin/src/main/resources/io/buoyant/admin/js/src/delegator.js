"use strict";

define([
  'jQuery',
  'src/dtab_viewer',
  'template/compiled_templates'
], function($, DtabViewer, compiledTemplates) {
  var Delegator = (function() {
    var templates = {
      dentry: compiledTemplates.dentry,
      node: compiledTemplates.delegatenode,
      errorModal: compiledTemplates.error_modal,
      delegator: compiledTemplates.delegator
    }

    function renderAll(resp) {
      $('.result').html(renderNode(resp));
    }

    function renderNode(obj, weight){
      switch(obj.type) {
        case "delegate": obj.isDelegate = true; obj.child = renderNode(obj.delegate); break;
        case "transformation":
          obj.isLeaf = true;
          obj.dentry = obj.tree.dentry;
          obj.tree.dentry = null;
          obj.tree.transformation = obj.name;
          obj.tree.isTransformation = true;
          obj.bound.transformed = renderNode(obj.tree);
          obj.child = renderNode(obj.bound);
          break;
        case "alt": obj.isAlt = true; obj.child = obj.alt.map(function(e,_i){ return renderNode(e); }).join(""); break;
        case "union": obj.isUnion = true; obj.child = obj.union.map(function(e,_i){ return renderNode(e.tree, e.weight); }).join(""); break;
        case "neg": obj.isNeg = true; break;
        case "fail": obj.isFail = true; break;
        case "leaf": obj.isLeaf = true; obj.child = renderNode(obj.bound); break;
        case "exception": obj.isException = true; break;
      }
      obj.weight = weight;
      return templates.node(obj);
    }

    return function($root, namespace, dtab, dtabBase) {
      $root.html(templates.delegator({}));

      $("#dtab").html(dtab.map(function(e) {
        return templates.dentry(e);
      }.bind(this)).join(""));

      var dtabViewer = new DtabViewer(dtabBase, templates.dentry);
      $('#path-input').val(decodeURIComponent(window.location.hash).slice(1)).focus();

      $('.go').click(function(e){
        e.preventDefault();
        var path = $('#path-input').val();
        window.location.hash = encodeURIComponent(path);
        var request = $.get(
          "delegator.json?" + $.param({ path: path, dtab: dtabViewer.dtabStr(), namespace: namespace }),
          renderAll.bind(this));
        request.fail(function( jqXHR ) {
          $(".error-modal").html(templates.errorModal(jqXHR.responseText));
          $('.error-modal').modal();
        });
      });

      // delegate when enter is pressed and input is focused
      $(document).keypress(function(e) {
        if(e.which == 13 && $('#path-input').is(":focus")) {
          $('.go').click();
        }
      });

      return {
        //no methods to return yet :)
      };
    };
  })();
  return Delegator;
});
