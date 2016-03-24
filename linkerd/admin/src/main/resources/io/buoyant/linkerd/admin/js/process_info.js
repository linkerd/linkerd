/**
 * Process info for topline summary
 */
var ProcInfo = (function() {

  var msToStr = new MsToStringConverter();
  var bytesToStr = new BytesToStringConverter();
  var refreshUri = "/admin/metrics";

  function pretty(name, value) {
    switch (name) {
      case "jvm/uptime": return msToStr.convert(value);
      case "jvm/mem/current/used": return bytesToStr.convert(value);
      case "jvm/gc/msec": return msToStr.convert(value);
      default: return value;
    }
  }

  function render(data) {
    var json = $.parseJSON(data);
    _(json).each(function(obj) {
      var id = obj.name.replace(/\//g, "-");
      var value = pretty(obj.name, obj.value);
      $("#"+id).text(value);
    });
  }

  /**
   * Returns a function that may be called to trigger an update.
   */
  return function() {
    var url = refreshUri + "?";

    $("#process-info ul li").each(function(i) {
      var key = $(this).data("key");
      if (key) {
        url += "&m="+key;
      }
    });

    function update() {
      $.ajax({
        url: url,
        dataType: "text",
        cache: false,
        success: render
      });
    }

    return {
      start: function(interval) { setInterval(update, interval); }
    };
  };
})();
