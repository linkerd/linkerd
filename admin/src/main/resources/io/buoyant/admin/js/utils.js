/*! modified from twitter-server | (c) 2015 Twitter, Inc. | http://www.apache.org/licenses/LICENSE-2.0 */
"use strict";
/* globals SmoothieChart, TimeSeries */

function MsToStringConverter() {
  this.msInSecond = 1000
  this.msInMinute = this.msInSecond*60
  this.msInHour = this.msInMinute*60
  this.msInDay = this.msInHour*24
  this.msInYear = this.msInDay*365.242
}

MsToStringConverter.prototype.showMilliseconds = function(ms) { return ms + "ms"}
MsToStringConverter.prototype.showSeconds = function(seconds) { return seconds + "s" }
MsToStringConverter.prototype.showMinutes= function(minutes) { return minutes + "m" }
MsToStringConverter.prototype.showHours = function(hours) { return hours + "h" }
MsToStringConverter.prototype.showDays = function(days) { return days + "d" }
MsToStringConverter.prototype.showYears = function(years) { return years + "y" }

MsToStringConverter.prototype.convert = function(ms) {
  var years = (ms/this.msInYear).toFixed(0)
  var days = Math.floor(((ms - this.msInYear*years)/this.msInDay))
  var hours = Math.floor((ms - this.msInYear*years - this.msInDay*days)/this.msInHour)
  var minutes = Math.floor((ms - this.msInYear*years - this.msInDay*days - this.msInHour*hours)/this.msInMinute)
  var seconds = Math.floor((ms - this.msInYear*years - this.msInDay*days - this.msInHour*hours - this.msInMinute*minutes)/this.msInSecond)

  if(ms < this.msInSecond) return this.showMilliseconds(ms)
  else if(ms < this.msInMinute) return this.showSeconds(seconds)
  else if(ms < this.msInHour) return this.showMinutes(minutes) + " " + this.showSeconds(seconds)
  else if(ms < this.msInDay) return this.showHours(hours) + " " + this.showMinutes(minutes) + " " + this.showSeconds(seconds)
  else if(ms < this.msInYear) return this.showDays(days) + " " + this.showHours(hours) + " " + this.showMinutes(minutes)
  else return this.showYears(years) + " " + this.showDays(days) + " " + this.showHours(hours)
}

function BytesToStringConverter() {
  this.bytesInKB = 1024
  this.bytesInMB = this.bytesInKB*1024
  this.bytesInGB = this.bytesInMB*1024
}

BytesToStringConverter.prototype.showB = function(b) { return b + "B"}
BytesToStringConverter.prototype.showKB = function(kb) { return kb + "KB"}
BytesToStringConverter.prototype.showMB = function(mb) { return mb + "MB"}
BytesToStringConverter.prototype.showGB = function(gb) { return gb + "GB"}

BytesToStringConverter.prototype.convert = function(b) {
  if(b < this.bytesInKB) return this.showB(b)
  else if(b < this.bytesInMB) return this. showKB((b/this.bytesInKB).toFixed(1))
  else if(b < this.bytesInGB) return this.showMB((b/this.bytesInMB).toFixed(1))
  else return this.showGB((b/this.bytesInGB).toFixed(1))
}

/*
 * Success Rate Utils
 */

function SuccessRate(success, failures) {
  this.requests = success + failures;
  this.success = success;
  this.failures = failures;

  this.successRate = -1;
  if (this.requests == 0) {
    this.successRate = -1;
  } else if (this.success != 0) {
    this.successRate = this.success / this.requests;
  } else {
    this.successRate = 1 - (this.failures / this.requests);
  }
}

SuccessRate.prototype.get = function() {
  return this.successRate;
}

SuccessRate.prototype.prettyRate = function() {
  if (this.successRate < 0) {
    return "N/A"
  } else {
    return (100*this.successRate).toFixed(2) + "%";
  }
}

SuccessRate.prototype.rateStyle = function() {
  if (this.successRate < 0.0) {
    return "sr-undefined";
  } else if (this.successRate < 0.9) {
    return "sr-bad";
  } else if (this.successRate < 0.99) {
    return "sr-poor";
  } else {
    return "sr-good";
  }
}

/*
 * Smoothie Utils
 */

function UpdateableChart(userOpts, canvas, widthFn, tsOpts) {
  this.canvas = canvas;
  this.widthFn = widthFn;

  this.chart = undefined;
  this.timeout = undefined;
  this.tsMap = undefined;
  this.tsOpts = tsOpts;

  var defaults = {
    grid: {
      strokeStyle: 'rgba(39,66,69,0.3)',
      fillStyle: 'rgba(255,255,255, 0)',
      verticalSections: 2,
      millisPerLine: 5000
    },
    timestampFormatter: SmoothieChart.timeFormatter,
    labels: {
      fillStyle: 'rgb(0,0,0)',
      fontSize: 12,
      precision: 0
    },
    millisPerPixel: 30
  }

  this.chart = new SmoothieChart(_.merge(defaults, userOpts));
  this.chart.streamTo(this.canvas, 1000);

  window.addEventListener('resize', this._resize.bind(this), false);
  this._resize();
}

UpdateableChart.prototype.setMetric = function(metric) {
  this.setMetrics([{ name: metric, color: "83,176,196"}]);
}

UpdateableChart.prototype.setMetrics = function(metrics, suppressUpdates) {
  clearTimeout(this.timeout);

  if (this.tsMap !== undefined) {
    _.map(this.tsMap, function(ts){
      this.chart.removeTimeSeries(ts);
    });
  }

  this.tsMap = {};
  _.each(metrics, this._addMetric.bind(this));

  this.metrics = _.map(metrics, 'name');
  if (!suppressUpdates)
    this._getMetrics();
}

UpdateableChart.prototype.addMetrics = function(metrics) {
  _.each(metrics, this._addMetric.bind(this));
  this.metrics = this.metrics.concat(_.map(metrics, 'name'));
}

UpdateableChart.prototype._addMetric = function(metric) {
  var tsOptions = this.tsOpts ? this.tsOpts(metric.name) :  {
      strokeStyle: "rgb(" + metric.color + ")",
      fillStyle: "rgba(" + metric.color + ",0.3)",
      lineWidth: 3
    };

  this.tsMap[metric.name] = new TimeSeries();
  this.chart.addTimeSeries(
    this.tsMap[metric.name],
    tsOptions
  );
}

UpdateableChart.prototype._resize = function() {
  this.canvas.width = this.widthFn();
}

UpdateableChart.prototype._getMetrics = function() {
  if (this.metrics.length) {
    $.ajax({
      url: "/admin/metrics",
      type: "POST",
      dataType: "json",
      data: $.param({m: this.metrics}, true), //use shallow/traditional encoding
      cache: false,
      success: (function(data) {
        this.updateMetrics(data);
        this.timeout = setTimeout(this._getMetrics.bind(this), 1000);
      }).bind(this)
    });
  } else {
    this.timeout = setTimeout(this._getMetrics.bind(this), 1000);
  }
}

UpdateableChart.prototype.updateMetrics = function(data) {
  _.each(data, function(datum){
    var ts = this.tsMap[datum.name];
    if (!ts) {
      this._addMetric(datum);
      ts = this.tsMap[datum.name];
    }
    ts.append(new Date().getTime(), datum.delta);
  }.bind(this));

  $(this.canvas).trigger(
    "stat",
    [
      this.metrics,
      _.sumBy(data, 'delta')
    ]
  );
}

