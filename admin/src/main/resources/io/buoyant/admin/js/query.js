/* exported Query */

/**
 * Util for building regexes that map over specifically formatted metric names.
 * `Query.clientQuery().build()` matches all possible client metrics.
 *  Use defined helpers to make the query more specific.
 */
var Query = function() {
  function escape(string) {
    return string.replace(/[-\/\\^$*+?.()|[\]{}]/g, '\\$&');
  }

  function toR(arr) {
    if (arr === "*" || !arr || arr.length === 0) {
      return "(.*)";
    } else {
      return "(" + _.map(arr, escape).join("|") + ")";
    }
  }

  var generalQuery = function() {
    var query = {
      routerLabels: [],
      metricNames: []
    };
    query.allRouters = function() {
      query.routerLabels = "*";
      return query;
    }
    query.withRouter = function(router) {
      if (_.isArray(query.routerLabels))
        query.routerLabels.push(router);
      return query;
    }
    query.withRouters = function(routers) {
      if (_.isArray(query.routerLabels))
        query.routerLabels = query.routerLabels.concat(routers);
      return query;
    }
    query.allMetrics = function() {
      query.metricNames = "*";
      return query;
    }
    query.withMetric = function(metric) {
      if (_.isArray(query.metricNames))
        query.metricNames.push(metric);
      return query;
    }
    query.withMetrics = function(metrics) {
      if (_.isArray(query.metricNames))
        query.metricNames = query.metricNames.concat(metrics);
      return query;
    }
    return query;
  }

  var clientQuery = function() {
    var q = generalQuery();
    q.clientLabels = [];

    q.allClients = function() {
      q.clientLabels = "*";
      return q;
    }
    q.withClient = function(client) {
      if (_.isArray(q.clientLabels))
        q.clientLabels.push(client);
      return q;
    }
    q.withClients = function(clients) {
      if (_.isArray(q.clientLabels))
        q.clientLabels = q.clientLabels.concat(clients);
      return q;
    }
    q.build = function() {
      return new RegExp(
        ["^rt\/", toR(q.routerLabels), "\/dst\/id\/", toR(q.clientLabels), "\/", toR(q.metricNames), "$"].join(""));
    }

    return q;
  }

  var serverQuery = function() {
    var q = generalQuery();
    q.serverLabels = [];

    q.allServers = function() {
      q.serverLabels = "*";
      return q;
    }
    q.withServer = function(server) {
      if (_.isArray(q.serverLabels))
        q.serverLabels.push(server);
      return q;
    }
    q.withServers = function(servers) {
      if (_.isArray(q.serverLabels))
        q.serverLabels.concat(servers);
      return q;
    }
    q.build = function() {
      return new RegExp(["^rt\/", toR(q.routerLabels), "\/srv\/", toR(q.serverLabels), "\/", toR(q.metricNames), "$"].join(""));
    }

    return q;
  }

  function matchesQuery(metricName, q) {
    return metricName.search(q) >= 0;
  }

  function find(query, metrics) {
    return _.find(metrics, function(m) {
      return matchesQuery(m.name ? m.name : m, query);
    });
  }

  function filter(query, metrics) {
    return _.filter(metrics, function(m) {
      return matchesQuery(m.name ? m.name : m, query);
    });
  }

  return {
    serverQuery: serverQuery,
    clientQuery: clientQuery,
    find: find,
    filter: filter
  };

}();
