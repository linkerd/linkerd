/*! modified from twitter-server | (c) 2015 Twitter, Inc. | http://www.apache.org/licenses/LICENSE-2.0 */
"use strict";

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

function SuccessRate(requests, success, failures) {
  this.requests = requests;
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

function UpdateableChart(userOpts, canvas, widthFn) {
  this.canvas = canvas;
  this.widthFn = widthFn;

  this.chart = undefined;
  this.timeout = undefined;
  this.ts = undefined;

  var defaults = {
    grid: {
      strokeStyle: 'rgba(39,66,69,0.3)',
      fillStyle: 'rgb(255,255,255)',
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

  this.chart = new SmoothieChart($.extend(defaults, userOpts));
  this.chart.streamTo(this.canvas, 1000);

  window.addEventListener('resize', this._resize.bind(this), false);
  this._resize();
}

UpdateableChart.prototype.setMetric = function(metric) {
  clearTimeout(this.timeout);

  if (this.ts !== undefined) {
    this.chart.removeTimeSeries(this.ts);
  }
  this.ts = new TimeSeries();
  this.chart.addTimeSeries(
    this.ts,
    {
      strokeStyle: 'rgb(83,176,196)',
      fillStyle: 'rgba(83,176,196,0.3)',
      lineWidth: 3
    }
  );

  this._getMetric(metric);
}

UpdateableChart.prototype._resize = function() {
  this.canvas.width = this.widthFn();
}

UpdateableChart.prototype._getMetric = function(metric) {
  $.ajax({
    url: "/admin/metrics?m=" + metric,
    dataType: "json",
    cache: false,
    success: (function(data) {
      if (data[0] !== undefined) {
        this.ts.append(new Date().getTime(), data[0].delta);
      }
      $(this.canvas).trigger(
        "stat",
        [
          metric,
          data[0] && data[0].delta
        ]
      );

      this.timeout = setTimeout(this._getMetric.bind(this, metric), 1000);
    }).bind(this)
  });
}
