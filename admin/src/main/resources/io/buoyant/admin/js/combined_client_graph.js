var CombinedClientGraph = (function() {
  function clientToMetric(client) {
    return {name: client, color: ""}; //TODO: move to clientName only after v2 migration
  }

  return function(metricsCollector, routerName, $root, colors) {
    var clientColors = colors;
    var query = Query.clientQuery().withRouter(routerName).withMetric("requests").build();

    function timeseriesParams(name) {
      return {
        strokeStyle: clientColors[name.match(Query.clientQuery().build())[2]].color,
        lineWidth: 2
      };
    };

    var chart = new UpdateableChart(
      {
        minValue: 0,
        grid: {
          strokeStyle: '#878787',
          verticalSections: 1,
          millisPerLine: 10000,
          borderVisible: false
        },
        labels: {
          fillStyle: '#878787',
          fontSize: 12,
          precision: 0
        },
        millisPerPixel: 60
      },
      $root[0],
      function() {
        return 1161;
      },
      timeseriesParams
    );

    var desiredMetrics = _.map(Query.filter(query, metricsCollector.getCurrentMetrics()), clientToMetric);
    chart.setMetrics(desiredMetrics, timeseriesParams, true);

    metricsCollector.registerListener(function(data) {
      var filteredData = Query.filter(query, data.specific);
      chart.updateMetrics(filteredData);
    }, function(metrics) { return Query.filter(query, metrics); });
    return {
      updateColors: function(newColors) {
        clientColors = newColors;
      }
    };
  };
})();
