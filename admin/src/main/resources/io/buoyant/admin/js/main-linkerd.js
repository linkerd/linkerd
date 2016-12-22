require.config({
  paths: {
    'jQuery': 'lib/jquery.min',
    'lodash': 'lib/lodash.min',
    'Handlebars': 'lib/handlebars-v4.0.5',
    'bootstrap': 'lib/bootstrap.min',
    'Smoothie': 'js/lib/smoothie'
  },
  shim: {
    'jQuery': {
      exports: '$'
    },
    'lodash': {
      exports: '_'
    },
    'bootstrap': {
      deps : ['jQuery'],
      exports: 'Bootstrap'
    }
  }
});

require([
  'jQuery',
  'lodash',
  'bootstrap',
  'src/admin',
  'src/dashboard',
  'src/delegate',
  'src/dashboard_delegate'
], function (
  $, _, bootstrap,
  adminPage,
  dashboard,
  linkerdDtabPlayground,
  namerdDtabPlayground
) {
  // poor man's routing
  if (window.location.pathname.indexOf("delegator") === 1) {
    adminPage.initialize(true);
    new linkerdDtabPlayground();
  } else {
    adminPage.initialize();
    new dashboard();
  }
});
