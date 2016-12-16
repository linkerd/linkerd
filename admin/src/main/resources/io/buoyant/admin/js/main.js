require.config({
  paths: {
    'jQuery': 'lib/jquery.min',
    'lodash': 'lib/lodash.min',
    'Handlebars': 'lib/handlebars-v4.0.5',
    'bootstrap': 'lib/bootstrap.min',
    'SmoothieChart': 'lib/smoothie'
  },
  shim: {
    'jQuery': {
        exports: '$'
    },
    'lodash': {
        exports: '_'
    },
    'Handlebars': {
        exports: 'Handlebars'
    },
    'bootstrap': {
        deps : [ 'jQuery'],
        exports: 'Bootstrap'
    },
    'SmoothieChart': {
        exports: 'SmoothieChart'
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
  ],
  function (
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
    adminPage.initialize();

    // poor man's routing
    if (window.location.pathname.indexOf("delegator") === 1) {
      new linkerdDtabPlayground();
    } else {
      new dashboard();
    }
  }
});
