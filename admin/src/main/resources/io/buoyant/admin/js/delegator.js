var Delegator = (function() {
  var templates;

  function renderAll(resp) {
    $('.result').html(renderNode(resp));
  }

  function renderNode(obj){
    switch(obj.type) {
      case "delegate": obj.isDelegate = true; obj.child = renderNode(obj.delegate); break;
      case "alt": obj.isAlt = true; obj.child = obj.alt.map(function(e,i){ return renderNode(e); }).join(""); break;
      case "neg": obj.isNeg = true; break;
      case "fail": obj.isFail = true; break;
      case "leaf": obj.isLeaf = true; obj.child = renderNode(obj.bound); break;
      case "exception": obj.isException = true; break;
    }
    return templates.node(obj);
  }

  return function($root, dtab, t) {
    templates = t
    $root.html(templates.delegator({}));

    var dtabViewer = new DtabViewer(dtab, templates.dentry);
    $('#path-input').val(decodeURIComponent(window.location.hash).slice(1)).focus();

    $('.go').click(function(e){
      e.preventDefault();
      var path = $('#path-input').val();
      window.location.hash = encodeURIComponent(path);
      var request = $.get(
        "/delegator.json?" + $.param({ path: path, dtab: dtabViewer.dtabStr() }),
        renderAll.bind(this));
      request.fail(function( jqXHR ) {
        $(".error-modal").html(templates.errorModal(jqXHR.statusText));
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
