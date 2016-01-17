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
 * - BigBoard: a big chart and set of stats about the most active server
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
      server = BigBoard.findMostActiveServer(routers.data);

  $(function() {
    var procInfo = ProcInfo(),
        bigBoard = BigBoard(server, summaryTemplate),
        interfaces = Interfaces(routers, ifacesTemplate);
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
    if (name === "jvm/uptime") return msToStr.convert(value);
    else if (name === "jvm/mem/current/used") return bytesToStr.convert(value);
    else if (name === "jvm/gc/msec") return msToStr.convert(value);
    else return value;
  }

  function render(data) {
    var json = $.parseJSON(data);
    for (var i = 0; i < json.length; i++) {
      var id = json[i].name.replace(/\//g, "-");
      var value = pretty(json[i].name, json[i].value);
      $("#"+id).text(value);
    }
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
 * Big summary board of "most active" server (by number of requests)
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
  var init = function(server, template) {
    // set up primary server requests chart
    chart.setMetric(server.prefix + "requests");

    // set up primary server metrics
    $('#request-stats').html(template({server: server, keys: summaryKeys}));

    // store primary server metric dom elements
    var metrics = {};
    $("#request-stats dd").each(function(i) {
      var key = $(this).data("key");
      if (key) {
        metrics[key] = $(this);
      }
    });

    var url = "/admin/metrics?m="+Object.keys(metrics).join("&m=");
    function update() {
      $.ajax({
        url: url,
        dataType: "json",
        cache: false,
        success: function(data) {
          for (var i = 0; i < data.length; i++) {
            metrics[data[i].name].text(data[i].delta);
          }
        }
      });
    };
    update();

    return {
      start: function(interval) { setInterval(update, interval); }
    };
  };

  /** Helper */
  init.findMostActiveServer = function(routers) {
    var active = {metrics:{requests:-1}, prefix:"rt/http/srv/127.0.0.1/4140/"};
    Object.keys(routers).forEach(function(name) {
      routers[name].servers.forEach(function(server) {
        if (server.metrics.requests > active.metrics.requests) {
          active = server;
        }
      });
    });
    return active;
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
      name: iface.router +"/"+ iface.label,
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
    ifaces.sort(function(a, b) {
      if (a.successRate != b.successRate) {
        if (a.successRate == -1) {
          return 1;
        } else if (b.successRate == -1) {
          return -1;
        } else {
          return a.successRate > b.successRate ? 1 : -1;
        }
      } else {
        return a.name > b.name ? 1 : -1;
      }
    });
    return ifaces;
  }

  function prepClient(client) {
    var iface = prepInterface(client);
    iface.lbSize = client.metrics["loadbalancer/size"] || 0;
    iface.lbAvail = client.metrics["loadbalancer/available"] || 0;
    return iface;
  }

  function prepClients(clients) {
    return sortBySuccess(clients.map(prepClient));
  }

  function prepServers(servers) {
    return sortBySuccess(servers.map(prepInterface));
  }

  function renderInterfaces(routers, template) {
    var clients = [],
        servers = [];

    Object.keys(routers).forEach(function(name) {
      var router = routers[name];
      servers = servers.concat(router.servers);
      Object.keys(router.dstIds).forEach(function(id) {
        clients.push(router.dstIds[id]);
      });
    });

    $('#client-info').html(template({name:'clients', interfaces: prepClients(clients)}));
    $('#server-info').html(template({name:'servers', interfaces: prepServers(servers)}));
  }

  /**
   * Renders interfacs, and then returns a function that may be called
   * to trigger an update.
   */
  return function(routers, template) {
    renderInterfaces(routers.data, template);
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
          renderInterfaces(routers.data, template);
        }
      });
    };

    return {
      start: function(interval) { setInterval(update, interval) }
    };
  };
})();
