"use strict";

define([
  'jQuery',
  'template/compiled_templates'
], function(
  $,
  templates
) {
  var template = templates.logging_row;
  var logLevels = ["ALL", "TRACE", "DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL", "FATAL", "OFF"];

  function modelToView(config) {
    var contextLevels = logLevels.map(function(level) {
      return {
        level: level,
        isActive: config.level == level
      };
    });
    return {
      logger: config.logger,
      logLevels: contextLevels
    }
  }

  function clickHandler(e) {
    e.preventDefault();
    var level = $(this).attr("data-level");
    var logger = $(this).attr("data-logger");
    var config = { level: level, logger: logger };
    $.post("logging.json", config, function() {
      if (logger == "root") {
        $.get("logging.json", function(logConfig) {
          var $table = $(".table tbody");
          $table.empty();
          for (var index in logConfig) {
            var config = logConfig[index];
            $table.append(template(modelToView(config)));
          }
        })
      }
    });
    $(this).closest("tr").replaceWith(template(modelToView(config)));
  }

  return function() {
    var logConfig = JSON.parse($("#logger-data").html());
    var $table = $(".table").on("click", "a.btn", clickHandler);

    for (var index in logConfig) {
      var config = logConfig[index];
      $table.append(template(modelToView(config)));
    }
  }
});
