/*! modified from twitter-server | (c) 2015 Twitter, Inc. | http://www.apache.org/licenses/LICENSE-2.0 */
"use strict";

var templates = {};

$.when(
  $.get("/files/template/interfaces.template"),
  $.get("/files/template/request_stats.template"),
  $.get("/admin/metrics.json")
).done(function(interfacesRsp, requestStatsRsp, metricsJson) {

  Handlebars.registerHelper('pluralize', function(number, single, plural) {
    if (number === 1) {
      return single;
    } else {
      return plural;
    }
  });

  templates.interfaces = Handlebars.compile(interfacesRsp[0]);
  templates.requestStats = Handlebars.compile(requestStatsRsp[0]);

  $(function() {
    loadProcInfo();
    loadMetricsInfo(metricsJson[0]);
    loadInterfacesInfo(metricsJson[0]);
  });
});

function loadProcInfo() {
  var url = $("#process-info").data("refresh-uri") + "?";

  $("#process-info ul li").each(function(i) {
    var key = $(this).data("key");
    if (key) {
      url += "&m="+key;
    }
  });

  var msToStr = new MsToStringConverter();
  var bytesToStr = new BytesToStringConverter();

  function pretty(name, value) {
    if (name === "jvm/uptime") return msToStr.convert(value)
    else if (name === "jvm/mem/current/used") return bytesToStr.convert(value)
    else if (name === "jvm/gc/msec") return msToStr.convert(value)
    else return value;
  }

  function renderProcInfo(data) {
    var json = $.parseJSON(data);
    for (var i = 0; i < json.length; i++) {
      var id = json[i].name.replace(/\//g, "-");
      var value = pretty(json[i].name, json[i].value);
      $("#"+id).text(value);
    }
  }

  // poll for top-line metrics (uptime, mem, etc)
  function fetchProcInfo() {
    $.ajax({
      url: url,
      dataType: "text",
      cache: false,
      success: renderProcInfo,
    });
  }
  fetchProcInfo();
  setInterval(fetchProcInfo, 1000);
}

// known interfaces we do not want to render in the ui
var blackList = [
  "k8s/",
  "consul/",
  "<function1>",
  "tracer/localhost",
];

// return an object of client and server names:
// {
//   clients: ['client_foo', 'client_bar', ...],
//   servers: ['server_baz', ...],
// }
function jsonToInterfaces(metricsJson) {
  var keys = $.grep(Object.keys(metricsJson), function(key) {
    return (
      key.endsWith("/requests") &&
      !blackList.some(function(elem, i, arr) {
        return key.indexOf(elem) != -1;
      })
    );
  });

  var interfaces = {
    clients: [],
    servers: [],
  };

  $.each(keys, function(i, key) {
    if (key.startsWith("clnt/")) {
      var client = key.substring("clnt/".length, key.lastIndexOf("/"));
      interfaces.clients.push(client);
    } else if (key.startsWith("srv/")) {
      var server = key.substring("srv/".length, key.lastIndexOf("/"));
      interfaces.servers.push(server);
    }
  });

  return interfaces;
}

function loadMetricsInfo(metricsJson) {

  // init chart
  var chart = new UpdateableChart(
    { minValue: 0 },
    document.getElementById("request-canvas"),
    function() {
      return window.innerWidth * 0.75;
    }
  );

  // try to guess the most relevant server
  var primaryServer = "http/127.0.0.1/4140"; // default

  var interfaces = jsonToInterfaces(metricsJson);
  if (interfaces.servers.length > 0) {
    var candidates = $.grep(interfaces.servers, function(iface) {
      return iface.indexOf("4140") != -1;
    });
    if (candidates.length > 0) {
      primaryServer = candidates[0];
    } else {
      candidates = $.grep(interfaces.servers, function(iface) {
        return iface.indexOf("http") != -1;
      });
      if (candidates.length > 0) {
        primaryServer = candidates[0];
      } else {
        primaryServer = interfaces.servers[0];
      }
    }
  }

  // set up primary server requests chart
  chart.setMetric("srv/" + primaryServer + "/requests");

  // set up primary server metrics
  $('#request-stats').html(templates.requestStats({
    server: primaryServer,
    keys: ['load', 'failures', 'success', 'requests'],
  }));

  // store primary server metric dom elements
  var metrics = {};
  $("#request-stats dd").each(function(i) {
    var key = $(this).data("key");
    if (key) {
      metrics[key] = $(this);
    }
  });

  // poll for primary server metrics
  function fetchMetricsInfo() {
    $.ajax({
      url: "/admin/metrics?m="+Object.keys(metrics).join("&m="),
      dataType: "json",
      cache: false,
      success: function(data) {
        for (var i = 0; i < data.length; i++) {
          metrics[data[i].name].text(data[i].delta);
        }
      }
    });
  }
  fetchMetricsInfo();
  setInterval(fetchMetricsInfo, 1000);
}

function loadInterfacesInfo(metricsJson) {

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
  function prepInterfacesForHB(interfaces, metricsJson, isClient) {
    var interfacesHB = [];

    $.each(interfaces, function(i, iface) {
      var prefix = "srv/" + iface;
      if (isClient) {
        prefix = "clnt/" + iface;
      }

      var requests = metricsJson[prefix + "/requests"] || 0;
      var success = metricsJson[prefix + "/success"] || 0;
      var failures = metricsJson[prefix + "/failures"] || 0;

      var successRate = new SuccessRate(requests, success, failures);

      var interfaceHB = {
        name: iface,
        requestsKey: prefix + "/requests",
        requests: requests,
        success: success,
        failures: failures,
        connections: metricsJson[prefix + "/connections"] || 0,
        successRate: successRate.get(),
        prettyRate: successRate.prettyRate(),
        rateStyle: successRate.rateStyle(),
      };

      if (isClient) {
        interfaceHB.client = true;
        interfaceHB.lbSize = metricsJson[prefix + "/loadbalancer/size"] || 0;
        interfaceHB.lbAvail = metricsJson[prefix + "/loadbalancer/available"] || 0;
      }

      interfacesHB.push(interfaceHB);
    });

    // sort by ascending success rate
    interfacesHB.sort(function(a, b) {
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

    return interfacesHB;
  }

  function renderInterfaces(metricsJson) {
    var interfaces = jsonToInterfaces(metricsJson);

    $('#client-info').html(
      templates.interfaces({
        name: 'clients',
        interfaces: prepInterfacesForHB(interfaces.clients, metricsJson, true),
      })
    );
    $('#server-info').html(
      templates.interfaces({
        name: 'servers',
        interfaces: prepInterfacesForHB(interfaces.servers, metricsJson, false),
      })
    );
  }
  renderInterfaces(metricsJson);
  $(".interfaces").on("click", ".interface", function() {
    window.location = $(this).find("a").attr("href");
    return false;
  });

  // poll for all interface metrics
  function fetchInterfacesInfo() {
    $.ajax({
      url: "/admin/metrics.json",
      dataType: "json",
      cache: false,
      success: renderInterfaces,
    });
  }
  setInterval(fetchInterfacesInfo, 1000);
}
