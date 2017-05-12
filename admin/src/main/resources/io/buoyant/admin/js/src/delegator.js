"use strict";

define([
  'lodash',
  'jQuery',
  'src/dtab_viewer',
  'template/compiled_templates'
], function(_, $, DtabViewer, compiledTemplates) {
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

    const neg = "neg";
    const fail = "fail";
    const success = "success";

    function treeType(obj){
      switch(obj.type) {
        case "delegate": return treeType(obj.delegate);
        case "transformation": return treeType(obj.tree);
        case "alt":
          for (var i = 0; i < obj.alt.length; i++) {
            var subtreeType = treeType(obj.alt[i]);
            if (subtreeType != neg) {
              return subtreeType;
            }
          }
          return neg;
        case "union":
          if (obj.union.some(function(e,_i){return treeType(e.tree) == fail;})) {
            return fail;
          } else if (obj.union.some(function(e,_i){return treeType(e.tree) == success;})) {
            return success;
          } else {
            return neg;
          }
        case "neg": return neg;
        case "fail": return fail;
        case "leaf": return treeType(obj.bound);
        case "exception": return fail;
      }
      if (obj.addr.type == "neg") {
        return neg;
      } else {
        return success;
      }
    }

    function renderNode(obj, primary, weight){
      var ttype = treeType(obj);
      switch(obj.type) {
        case "delegate":
          obj.isDelegate = true;
          obj.child = renderNode(obj.delegate, primary);
          break;
        case "transformation":
          obj.isLeaf = true;
          obj.dentry = obj.tree.dentry;
          obj.tree.dentry = null;
          obj.tree.transformation = obj.name;
          obj.tree.isTransformation = true;
          obj.bound.transformed = renderNode(obj.tree, primary);
          if (ttype != success) {
            obj.bound.addr.type = "neg";
          }
          obj.child = renderNode(obj.bound, primary);
          break;
        case "alt":
          obj.isAlt = true;
          var foundPrimaryBranch = false;
          obj.child = obj.alt.map(function(e,_i){
            if (primary && !foundPrimaryBranch && treeType(e) != neg){
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
        case "neg":
          obj.isNeg = true;
          break;
        case "fail":
          obj.isFail = true;
          break;
        case "leaf":
          obj.isLeaf = true;
          obj.child = renderNode(obj.bound, primary);
          break;
        case "exception":
          obj.isException = true;
          break;
      }
      obj.weight = weight;
      obj.isPrimary = primary;
      obj.isSuccess = ttype == success;
      return templates.node(obj);
    }

    return function($root, namespace, dtab, dtabBase) {
      $root.html(templates.delegator({}));

      $("#dtab").html(dtab.map(function(e) {
        return templates.dentry(e);
      }.bind(this)).join(""));

      if (!_.isEmpty(dtab)) {
        $(".namerd-dtab-warning").removeClass("hide");
      }

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
