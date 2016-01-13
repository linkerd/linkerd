"use strict";

$(function() {

  $("#support-request").submit(function(event) {
    var form = $(this);
    var email = $("#email").val();
    var description = $("#description").val();
    var procinfo = $("#support-procinfo").text();
    var body = JSON.stringify({
      "description": description,
      "public": false,
      "files": {
        "processInfo": {
          content: procinfo
        }
      }
    });

    $.post("https://api.github.com/gists", body).done(function(gist) {
      window.location = form.attr('action')+"?cc="+email+"&body="+encodeURIComponent(
        "https://gist.github.com/" + gist["id"]
      );
    });

    event.preventDefault();
  });

  $("#submit-support-request").click(function() {
    $('#support-request').submit();
  });

  $('#support-modal').on('show.bs.modal', function (e) {
    var $processInfo = $("#support-procinfo");

    $.when(
      $.ajax("/flags"),
      $.ajax("/admin/metrics.json"),
      $.ajax("/admin/server_info"),
      $.ajax({url: "/admin/lint", dataType: "json"}),
      $.ajax({url: "/admin/threads", dataType: "json"}),
      $.ajax("/admin/announcer"),
      $.ajax("/admin/dtab"),
      $.ajax({url: "/admin/registry.json", dataType: "json"})
    ).done(function(
      flags,
      metrics,
      server_info,
      lint,
      threads,
      announcer,
      dtab,
      registry
    ) {
      $processInfo.html(
        JSON.stringify({
          "flags": flags[0],
          "metrics": metrics[0],
          "server_info": server_info[0],
          "lint": lint[0],
          "threads": threads[0],
          "announcer": announcer[0],
          "dtab": dtab[0],
          "registry": registry[0]
        }, null, ' '));
      });
  });
});
