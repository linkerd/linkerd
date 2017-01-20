"use strict";

define([
  'jQuery',
  'Handlebars',
  'text!template/logging_row.template'
], function(
  $, Handlebars,
  loggingTemplate
) {
  var template = Handlebars.compile(loggingTemplate);
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
    $(this).closest("tr").replaceWith(template(modelToView(config)));
    $.get("logging.json", config);
  }

  return function() {
    var $table = $(".table").on("click", "a.btn", clickHandler);
    var logConfig = JSON.parse($("#logger-data").html());

    for (var index in logConfig) {
      var config = logConfig[index];
      $table.append(template(modelToView(config)));
    }
  }
});