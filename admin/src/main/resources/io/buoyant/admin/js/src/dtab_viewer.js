"use strict";

define(['jQuery'], function($) {
  function DtabViewer(initialDtab, dentryTemplate) {
    this.dtab = initialDtab;
    this.template = dentryTemplate;

    this.render();

    this._resize($(".dentry-prefix"), $(".dentry-dst"))
    window.addEventListener('resize', this._resize.bind(this, $(".dentry-prefix"), $(".dentry-dst")), false);

    $('#edit-dtab-btn').click(this._toggleEdit.bind(this));

    $('#save-dtab-btn').click(function(_e){
      var text = $("#dtab-input").val().replace(/\s+/g, '');
      var dentries = text.split(";");
      if (dentries[dentries.length - 1] === "") {
        dentries = dentries.slice(0, -1);
      }
      this.dtab = dentries.map(function(e,_i){
        var tuple = e.split("=>");
        return { prefix: tuple[0], dst: tuple[1] };
      });
      this.render();
      this._toggleEdit();
    }.bind(this));

    //make the input bigger when you hit enter
    $("#dtab-input").on('paste input', function () {
      if ($(this).outerHeight() > this.scrollHeight){
        $(this).height(1);
      }
      while ($(this).outerHeight() < this.scrollHeight + parseFloat($(this).css("borderTopWidth")) + parseFloat($(this).css("borderBottomWidth"))){
        $(this).height($(this).height() + 1);
      }

      $("#save-dtab-btn.disabled").removeClass("disabled");
    });
  }

  DtabViewer.prototype._toggleEdit = function() {
    $("#dtab-base, #dtab-edit").toggleClass("hide");

    if($('#edit-dtab-btn').hasClass("active")) {
      $('#edit-dtab-btn').removeClass("active").text("Edit");
      $("#save-dtab-btn").addClass("hide disabled");
      this._renderDtabInput();
    } else {
      $('#edit-dtab-btn').addClass("active").text("Cancel");
      $("#save-dtab-btn").removeClass("hide");
    }
  };

  DtabViewer.prototype._resize = function($prefix, $dst) {
    $(".dentry-prefix").attr("width", null);
    $(".dentry-dst").attr("width", null);
    console.log("resize");

    var prefixWidth = $prefix.width()
    var dstWidth = $dst.width();
    var maxDefaultWidth = Math.max(prefixWidth, dstWidth);

    var prefixContentWidth = $prefix.find(".prefix-content").width();
    var dstContentWidth = $dst.find(".dst-content").width();
    var maxContentWidth = Math.max(prefixContentWidth, dstContentWidth);

    console.log(prefixContentWidth, dstContentWidth);
    console.log(maxDefaultWidth, maxContentWidth);

    if (maxContentWidth < maxDefaultWidth) {
      $prefix.width(maxContentWidth);
      $dst.width(maxContentWidth);
    }
  }

  DtabViewer.prototype.render = function() {
    this._renderDtabHtml();
    this._renderDtabInput();

    //dentry click handlers
    $(".dentry-prefix").click(this._activateDentries.bind(this, "data-dentry-prefix"));
    $(".dentry-dst").click(this._activateDentries.bind(this, "data-dentry-dst"));
  }

  DtabViewer.prototype._renderDtabHtml = function() {
    $("#dtab-base").html(this.dtab.map(function(e,_i) {
      return this.template(e);
    }.bind(this)).join(""));
  }

  DtabViewer.prototype._renderDtabInput = function() {
    $("#dtab-input").val(this.dtab.map(this._dentryStr).join(";\n"));
  }

  DtabViewer.prototype._activateDentries = function(attributeName, e){
    var targetWasAlreadyActive = $(e.target).hasClass("active");
    $(".active").removeClass("active");

    if (!targetWasAlreadyActive) {
      var attr = $(e.target).attr(attributeName);
      $('#dtab-base [data-dentry-dst="'+attr+'"]').addClass("active");
      $('[data-dentry-prefix="'+attr+'"]').addClass("active");
    }
  };

  DtabViewer.prototype.dtabStr = function() {
    return this.dtab.map(this._dentryStr).join(";")
  }

  DtabViewer.prototype._dentryStr = function(dentry) {
    return dentry.prefix + "=>" + dentry.dst;
  }

  return DtabViewer;
});

