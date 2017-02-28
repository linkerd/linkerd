// copied from twitter-server
var idleShown = true;
var stackOnOff = {};

function isStackOn(domId) {
  return domId in stackOnOff && stackOnOff[domId] == true;
}

function toggleStack(domId) {
  var wasOn = isStackOn(domId);
  stackOnOff[domId] = !wasOn;
  $('#' + domId).toggle();
}

function toggleAllStacks(stackTraceRowClass, idleThreadStackClass) {
  var isOn = $('#all_stacks_checkbox').prop("checked");
  $('.' + stackTraceRowClass).each(function() {
    var stackDomId = $(this).attr('id');

    var isIdle = $(this).hasClass(idleThreadStackClass);
    if (idleShown || !isIdle) {
      stackOnOff[stackDomId] = isOn;
      if (isOn)
        $(this).show();
      else
        $(this).hide();
    }
  });
}

function toggleIdle(idleThreadStackClass) {
  $('.idle_thread').toggle();
  idleShown = !idleShown;

  if (!idleShown) {
    $('.' + idleThreadStackClass).hide();
  } else {
    $('.' + idleThreadStackClass).each(function() {
      var domId = $(this).attr('id');
      var wasOn = isStackOn(domId);
      if (wasOn) {
        $(this).show();
      }
    });
  }
}

// initialize tooltips
$(function () {
  $('[data-toggle="popover"]').popover();
})
