/*! modified from twitter-server | (c) 2015 Twitter, Inc. | http://www.apache.org/licenses/LICENSE-2.0 */
"use strict";

/* globals getSelectedRouter, Namers, ProcInfo, Routers, SuccessRate, UpdateableChart */

/**
 * Number of millis to wait between data updates.
 */
var UPDATE_INTERVAL = 1000;

// configure handlebar to support pluralization
Handlebars.registerHelper('pluralize', function(number, single, plural) {
  return (number === 1) ? single : plural;
});

/*
 * There are 3 segments of the summary page:
 * - ProcInfo: a top-line set of info about linkerd's build/runtime
 * - BigBoard: a big chart and set of stats about all servers
 * - Interfaces: client and server widgets
 */
$.when(
  $.get("/files/template/process_info.template"),
  $.get("/files/template/interfaces.template"),
  $.get("/files/template/request_stats.template"),
  $.get("/admin/metrics.json")
).done(function(processInfoRsp, interfacesRsp, requestStatsRsp, metricsJson) {
  var ifacesTemplate = Handlebars.compile(interfacesRsp[0]),
      summaryTemplate = Handlebars.compile(requestStatsRsp[0]),
      routers = Routers(metricsJson[0]),
      namers = Namers(metricsJson[0]);

  $(function() {
    var selectedRouter = getSelectedRouter();
    var procInfo = ProcInfo(null, $("#process-info"), Handlebars.compile(processInfoRsp[0]), $("#linkerd-version").text()),
        bigBoard = BigBoard(selectedRouter, routers, summaryTemplate),
        interfaces = Interfaces(selectedRouter, routers, namers, ifacesTemplate);

    procInfo.start(UPDATE_INTERVAL);
    bigBoard.start(UPDATE_INTERVAL);
    interfaces.start(UPDATE_INTERVAL);
  });
});


/**
 * Big summary board of server requests
 */
var BigBoard = (function() {
  var summaryKeys = ['load', 'failures', 'success', 'requests'];
  var chart = new UpdateableChart(
    {minValue: 0},
    document.getElementById("request-canvas"),
    function() {
      return window.innerWidth * 0.75;
    }
  );
  var metricsParams = [];

  function requestBody() {
    return $.param({m: metricsParams}, true);
  }

  function clientToMetric(client) {
    return {name: client.prefix + "requests", color: client.color};
  }

  function clientsToMetricParam(clients) {
    return _(clients).map(function(client) {
      return _.map(summaryKeys, function(key) {
        return client.prefix + key;
      });
    }).flatten().value();
  }

  function addClients(clients) {
    chart.addMetrics(_.map(clients, clientToMetric));
    metricsParams = metricsParams.concat(clientsToMetricParam(clients));
  }

  /**
   * Returns a function that may be called to trigger an update.
   */
  var init = function(selectedRouter, routers, template) {
    // set up requests chart
    chart.setMetrics(_.map(routers.clients(selectedRouter), clientToMetric));

    routers.onAddedClients(addClients);

    // set up metrics
    $('#request-stats').html(template({keys: summaryKeys}));

    // store metric dom elements
    var metrics = {};
    $("#request-stats dd").each(function() {
      var key = $(this).data("key");
      if (key) {
        metrics[key] = $(this);
      }
    });

    metricsParams = clientsToMetricParam(routers.clients(selectedRouter));

    function update() {
      $.ajax({
        url: "/admin/metrics",
        type: "POST",
        dataType: "json",
        data: requestBody(),
        cache: false,
        success: function(data) {
          _.map(summaryKeys, function(key) {
            var filteredResponses = _.filter(data, function(d){ return d.name.indexOf(key) > 0 });
            metrics[key].text(_.sumBy(filteredResponses, 'delta'));
          });
        }
      });
    }
    update();

    return {
      start: function(interval) { setInterval(update, interval); }
    };
  };

  return init;
})();

/**
 * Per-client/server views of router stats.
 */
var Interfaces = (function() {

  // return a list of interfaces for use in Handlebars, sorted by ascending success rate
  // [
  //   {
  //     name: "client_foo",
  //     requestsKey: "clnt/client_foo/requests",
  //     requests: 10,
  //     success: 9,
  //     failures: 1,
  //     connections: 2,
  //     successRate: 0.9,
  //     prettyRate: "90%",
  //     rateStyle: "sr-poor",
  //     client: true,
  //     lbSize: 2,
  //     lbAvail: 2,
  //   },
  //   {
  //     name: "client_bar",
  //     requestsKey: "clnt/client_bar/requests",
  //     ...
  //   },
  //   ...
  // ]
  function prepInterface(iface) {
    var success  = iface.metrics["success"]  || 0,
        failures = iface.metrics["failures"] || 0,
        successRate = new SuccessRate(success, failures);
    return {
      name: (iface.router ? iface.router+"/" : "") + iface.label,
      requestsKey: iface.prefix + "requests",
      requests: success + failures,
      success: success,
      failures: failures,
      connections: iface.metrics["connections"] || 0,
      successRate: successRate.get(),
      prettyRate: successRate.prettyRate(),
      rateStyle: successRate.rateStyle()
    };
  }

  function sortBySuccess(ifaces) {
    return _.sortBy(ifaces, ['successRate', 'name']);
  }

  function prepClient(client) {
    var iface = prepInterface(client);
    iface.lbSize = client.metrics["loadbalancer/size"] || 0;
    iface.lbAvail = client.metrics["loadbalancer/available"] || 0;
    iface.color = "rgb(" + client.color + ")";
    return iface;
  }

  function prepClients(clients) {
    return sortBySuccess(clients.map(prepClient));
  }

  function prepServers(servers) {
    return sortBySuccess(servers.map(prepInterface));
  }

  function renderInterfaces(selectedRouter, routers, namers, template) {
    var namerIfaces = _.map(namers, prepInterface);

    $('#client-info').html(template({
      name:'router clients',
      interfaces: prepClients(routers.clients(selectedRouter))
    }));

    $('#server-info').html(template({
      name:'router servers',
      interfaces: prepServers(routers.servers(selectedRouter))
    }));

    $('#namer-info').html(template({
      name:'namer clients',
      interfaces: sortBySuccess(namerIfaces)
    }));
  }

  /**
   * Renders interfaces, and then returns a function that may be called
   * to trigger an update.
   */
  return function(selectedRouter, routers, namers, template) {
    var router = routers.data[selectedRouter];
    var namerData = router ? [] : namers.data;

    renderInterfaces(selectedRouter, routers, namerData, template);
    $(".interfaces").on("click", ".interface", function() {
      window.location = $(this).find("a").attr("href");
      return false;
    });

    function update() {
      $.ajax({
        url: "/admin/metrics.json",
        dataType: "json",
        cache: false,
        success: function(metrics) {
          routers.update(metrics);
          namers.update(metrics);
          renderInterfaces(selectedRouter, routers, namerData, template);
        }
      });
    }

    return {
      start: function(interval) { setInterval(update, interval) }
    };
  };
})();
