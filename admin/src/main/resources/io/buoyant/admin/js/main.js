require.config({
  paths: {
    'jQuery': 'lib/jquery.min',
    'lodash': 'lib/lodash.min',
    'Handlebars': 'lib/handlebars-v4.0.5',
    'bootstrap': 'lib/bootstrap.min',
    'SmoothieChart': 'lib/smoothie',
    // smoothie_timeseries is a direct copy of smoothie_timeseries
    // because I don't want to edit the source file, but this file exports
    // two things in non-AMD format (SmoothieChart and TimeSeries)
    'TimeSeries': 'lib/smoothie_copy'
  },
  shim: {
    'jQuery': {
      exports: '$'
    },
    'lodash': {
      exports: '_'
    },
    'bootstrap': {
      deps : [ 'jQuery'],
      exports: 'Bootstrap'
    },
    'SmoothieChart': {
      exports: 'SmoothieChart'
    },
    'TimeSeries': {
      exports: 'TimeSeries'
    }
  }
});

require([
  'jQuery',
  'lodash',
  'app/admin',
  'app/dashboard',
  'app/delegate',
  'app/dashboard_delegate'
], function (
  $, _,
  adminPage,
  dashboard,
  linkerdDtabPlayground,
  namerdDtabPlayground
) {
  if ($('title').text().indexOf("namerd") !== -1) {
    // namerd admin
    new namerdDtabPlayground();
  } else {
    // linkerd admin

    // poor man's routing
    if (window.location.pathname.indexOf("delegator") === 1) {
      adminPage.initialize(true);
      new linkerdDtabPlayground();
    } else {
      adminPage.initialize();
      new dashboard();
    }
  }
});
