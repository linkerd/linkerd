/* globals Query, UpdateableChart */
/* exported CombinedClientGraph */
var CombinedClientGraph = (function() {
  function clientToMetric(client) {
    return {name: client, color: ""}; //TODO: move to clientName only after v2 migration
  }

  return function(metricsCollector, routers, routerName, $root, colors) {
    var clientColors = colors;

    function timeseriesParams(name) {
      return {
        strokeStyle: clientColors[name.match(Query.clientQuery().build())[2]].color,
        lineWidth: 2
      };
    }

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
        return $(".router").first().width();  // get this to display nicely on various screen widths
      },
      timeseriesParams
    );

    var clients = _.map(routers.clients(routerName), 'label');

    var query = Query.clientQuery().withRouter(routerName).withClients(clients).withMetric("requests").build();
    var desiredMetrics = _.map(Query.filter(query, metricsCollector.getCurrentMetrics()), clientToMetric);
    chart.setMetrics(desiredMetrics, timeseriesParams, true);

    var count = 0;
    var metricsListener = function(data) {
      if (count < 5) {
        // Hacky bug fix: discard the first few data points to fix the issue
        // where the first values from /metrics are very large [linkerd#485]
        count++;
      } else {
        var clients = _.map(routers.clients(routerName), 'label');
        var query = Query.clientQuery().withRouter(routerName).withClients(clients).withMetric("requests").build();
        var filteredData = Query.filter(query, data.specific);
        chart.updateMetrics(filteredData);
      }
    };

    metricsCollector.registerListener(metricsListener, function(metrics) { return Query.filter(query, metrics); });
    return {
      updateColors: function(newColors) {
        clientColors = newColors;
      }
    };
  };
})();
