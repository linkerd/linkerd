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
      $('.result').html(renderNode(resp, true));
    }

    function isNeg(obj){
      switch(obj.type) {
        case "delegate": return isNeg(obj.delegate);
        case "transformation": return isNeg(obj.tree);
        case "alt": return obj.alt.some(function(e,_i){
            return isNeg(e);
          });
        case "union": return obj.alt.every(function(e,_i){
            return isNeg(e);
          });
        case "neg": return true;
        case "fail": return false;
        case "leaf": return false;
        case "exception": return false;
      }
    }

    function renderNode(obj, primary, weight){
      switch(obj.type) {
        case "delegate": obj.isDelegate = true; obj.child = renderNode(obj.delegate, primary); break;
        case "transformation":
          obj.isLeaf = true;
          obj.dentry = obj.tree.dentry;
          obj.tree.dentry = null;
          obj.tree.transformation = obj.name;
          obj.tree.isTransformation = true;
          obj.bound.transformed = renderNode(obj.tree, primary);
          obj.child = renderNode(obj.bound, primary);
          break;
        case "alt":
          obj.isAlt = true;
          var foundPrimaryBranch = false;
          obj.child = obj.alt.map(function(e,_i){
            if (primary && !foundPrimaryBranch && !isNeg(e)){
              foundPrimaryBranch = true;
              return renderNode(e, true);
            } else {
              return renderNode(e, false);
            }
          }).join("");
          break;
        case "union":
          obj.isUnion = true;
          obj.child = obj.union.map(function(e,_i){
            return renderNode(e.tree, primary, e.weight);
          }).join("");
          break;
        case "neg": obj.isNeg = true; break;
        case "fail": obj.isFail = true; break;
        case "leaf": obj.isLeaf = true; obj.child = renderNode(obj.bound, primary); break;
        case "exception": obj.isException = true; break;
      }
      obj.weight = weight;
      obj.primary = primary;
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
        var request = $.ajax({
            url: 'delegator.json',
            method: 'POST',
            contentType: 'application/json',
            data: JSON.stringify({ namespace: namespace, dtab: dtabViewer.dtabStr(), path: path })
        }).done(renderAll.bind(this));
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
