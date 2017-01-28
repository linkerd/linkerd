"use strict";

define([
  'jQuery',
  'lodash'
], function() {

  function displayPercent(percent) {
    return _.isNull(percent) ? " - " : Math.round(percent * 100) + "%";
  }

  function loadBalancerBarColor(percent) {
    return percent < 0.5 ? "orange" : "green";
  }

  function loadBalancerPercent(data) {
    if (!data) return null;

    var numer = data["loadbalancer/available"] || {};
    var denom = data["loadbalancer/size"] || {};
    var percent = (!denom || !denom.value) ? 0 : (numer.value || 0) / denom.value;

    return {
      percent: percent,
      label: {
        description: "Endpoints available",
        value: (numer.value || "-") + " / " + (denom.value || "-")
      }
    }
  }

  function retryBarColor(percent) {
    if (percent < 0.5) return "red";
    else if (percent < 0.75) return "orange";
    else return "green";
  }

  function retryBarPercent(data, configuredBudget) {
    var retryPercent = !data["requests"] ? null : (data["retries"] || 0) / data["requests"];
    var budgetRemaining = Math.max(configuredBudget - (retryPercent || 0), 0);
    var healthBarPercent = Math.min(budgetRemaining / configuredBudget, 1);

    return {
      percent: healthBarPercent,
      label: {
        description: "Retry budget available",
        value: displayPercent(budgetRemaining) + " / " + displayPercent(configuredBudget)
      },
      warningLabel: retryPercent < configuredBudget ? null : "budget exhausted"
    }
  }

  return {
    loadBalancerBarColor: loadBalancerBarColor,
    loadBalancerPercent: loadBalancerPercent,
    retryBarColor: retryBarColor,
    retryBarPercent: retryBarPercent
  }
});
