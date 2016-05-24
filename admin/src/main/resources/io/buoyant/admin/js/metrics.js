/*! modified from twitter-server | (c) 2015 Twitter, Inc. | http://www.apache.org/licenses/LICENSE-2.0 */
"use strict";

/* globals getSelectedRouter, Routers, UpdateableChart */

$.when(
  $.get("/files/template/metrics.template"),
  $.get("/admin/metrics.json")
).done(function(templateRsp, jsonRsp) {
  $(function() {
    init(Handlebars.compile(templateRsp[0]), jsonRsp[0]);
  });
});

function init(template, json) {

  var metricRe = /.*\.(avg|count|max|min|p50|p90|p95|p99|p9990|p9999|sum)$/;
  var routers = Routers(json);
  var selectedRouter = getSelectedRouter();
  var router = routers.data[selectedRouter];

  // init metrics browser
  var metrics = _(json)
    .map(function(value, name){
      if (!name.match(metricRe)) {
        if (router) { //filter on router if it exists
          var matchingRouter = routers.findMatchingRouter(name);
          return matchingRouter && matchingRouter.label == router.label ? name : null;
        } else {
          return name;
        }
      }
    })
    .compact()
    .sort()
    .value();

  $('.metrics').html(template({ 'metrics': metrics }));

  var canvas = document.getElementById("metrics-canvas");

  // init chart
  var chart = new UpdateableChart(
    {},
    canvas,
    function() {
      return $(".metrics-graph").width();
    }
  );

  var name = $("#metrics-title .name");
  var value = $("#metrics-title .value");

  $(canvas).on(
    "stat",
    function(event, metric, stat) {
      if (stat !== undefined) {
        name.text(metric);
        value.text(stat);
      } else {
        name.text(metric + " not found");
      }
    }
  );

  var selected = undefined;
  function setMetric(li) {
    if (selected !== undefined) selected.removeClass("selected");
    li.addClass('selected');
    selected = li;

    chart.setMetric(li.attr("id"));
  }

  $('.metrics-list li').on('click', function(e) {
    var li = $(e.target);
    var selector = "#"+li.attr("id");
    history.pushState(selector, null, selector);
    setMetric(li);
  });

  // load initial metric
  var initialMetric = locHashToObj(window.location.hash);
  if (initialMetric.length) {
    initialMetric[0].scrollIntoView(true);
  } else {
    initialMetric = $('.metrics-list li:first');
  }
  setMetric(initialMetric);

  // back/forward navigation
  window.onpopstate = function(e) {
    if (e.state !== null) {
      setMetric(locHashToObj(e.state));
    }
  };
}

function locHashToObj(locHash) {
  return $("#"+locHash
    .replace('#', '')
    // css chars to escape: !"#$%&'()*+,-./:;<=>?@[\]^`{|}~
    .replace(/(!|"|#|\$|%|&|'|\(|\)|\*|\+|,|-|\.|\/|:|;|<|=|>|\?|@|\[|\\|\]|\^|`|{|\||}|~)/g, "\\$1")
  )
}
