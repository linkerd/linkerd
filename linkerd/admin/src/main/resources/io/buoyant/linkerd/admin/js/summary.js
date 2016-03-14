/*! modified from twitter-server | (c) 2015 Twitter, Inc. | http://www.apache.org/licenses/LICENSE-2.0 */
"use strict";

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
  $.get("/files/template/interfaces.template"),
  $.get("/files/template/request_stats.template"),
  $.get("/admin/metrics.json")
).done(function(interfacesRsp, requestStatsRsp, metricsJson) {
  var ifacesTemplate = Handlebars.compile(interfacesRsp[0]),
      summaryTemplate = Handlebars.compile(requestStatsRsp[0]),
      routers = Routers(metricsJson[0]),
      namers = Namers(metricsJson[0]);

  $(function() {
    var selectedRouter = getSelectedRouter();
    var router = routers.data[selectedRouter];
    if (router)
      var clients = _.values(router.dstIds);
    else
      var clients = _(routers.data).values().flatMap(function(r){return _.values(r.dstIds);}).value();

    var procInfo = ProcInfo(),
        bigBoard = BigBoard(clients, summaryTemplate),
        interfaces = Interfaces(selectedRouter, routers, namers, ifacesTemplate);

    procInfo.start(UPDATE_INTERVAL);
    bigBoard.start(UPDATE_INTERVAL);
    interfaces.start(UPDATE_INTERVAL);
  });
});

/**
 * Process info
 */
var ProcInfo = (function() {

  var msToStr = new MsToStringConverter();
  var bytesToStr = new BytesToStringConverter();

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
    var url = $("#process-info").data("refresh-uri") + "?";
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

/**
 * Big summary board of server requests
 */
var BigBoard = (function() {
  var summaryKeys = ['load', 'failures', 'success', 'requests'],
      chart = new UpdateableChart(
        {minValue: 0},
        document.getElementById("request-canvas"),
        function() {
          return window.innerWidth * 0.75;
        }
      );

  /**
   * Returns a function that may be called to trigger an update.
   */
  var init = function(servers, template) {
    // set up requests chart
    chart.setMetrics(_.map(servers, function(server) {
      return {name: server.prefix + "requests", color: server.color};
    }));

    // set up metrics
    $('#request-stats').html(template({keys: summaryKeys}));

    // store metric dom elements
    var metrics = {};
    $("#request-stats dd").each(function(i) {
      var key = $(this).data("key");
      if (key) {
        metrics[key] = $(this);
      }
    });

    var metricsParams = _(servers).map(function(server) {
      return _.map(summaryKeys, function(key) {
        return server.prefix + key;
      });
    }).flatten().value();

    var url = "/admin/metrics?" + $.param({m: metricsParams}, true);
    function update() {
      $.ajax({
        url: url,
        dataType: "json",
        cache: false,
        success: function(data) {
          _.map(summaryKeys, function(key) {
            var filteredResponses = _.filter(data, function(d){ return d.name.indexOf(key) > 0 });
            metrics[key].text(_.sumBy(filteredResponses, 'delta'));
          });
        }
      });
    };
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
    var requests = iface.metrics["requests"] || 0,
        success  = iface.metrics["success"]  || 0,
        failures = iface.metrics["failures"] || 0,
        successRate = new SuccessRate(requests, success, failures);
    return {
      name: (iface.router ? iface.router+"/" : "") + iface.label,
      requestsKey: iface.prefix + "requests",
      requests: requests,
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

  function renderInterfaces(routers, namers, template) {
    var servers = _(routers)
      .map('servers')
      .flatten()
      .value();

    var clients = _(routers)
      .map(function(router) { return _.values(router.dstIds); })
      .flatten()
      .value();

    var namerIfaces = _.map(namers, prepInterface);

    $('#client-info').html(template({name:'router clients', interfaces: prepClients(clients)}));
    $('#server-info').html(template({name:'router servers', interfaces: prepServers(servers)}));
    $('#namer-info').html(template({name:'namer clients', interfaces: sortBySuccess(namerIfaces)}));
  }

  /**
   * Renders interfaces, and then returns a function that may be called
   * to trigger an update.
   */
  return function(selectedRouter, routers, namers, template) {
    var router = routers.data[selectedRouter];
    var routerData = router ? [router] : routers.data;
    var namerData = router ? [] : namers.data;

    renderInterfaces(routerData, namerData, template);
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
          renderInterfaces(routerData, namerData, template);
        }
      });
    };

    return {
      start: function(interval) { setInterval(update, interval) }
    };
  };
})();
