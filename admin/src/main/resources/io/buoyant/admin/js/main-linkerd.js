require.config({
  paths: {
    'jQuery': 'lib/jquery.min',
    'lodash': 'lib/lodash.min',
    'Handlebars': 'lib/handlebars-v4.0.5',
    'bootstrap': 'lib/bootstrap.min',
    'Smoothie': 'lib/smoothie',
    'text': 'lib/text'
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
  'src/logging'
], function (
  $, _, bootstrap,
  adminPage,
  dashboard,
  linkerdDtabPlayground,
  loggingConfig
) {
  // poor man's routing
  if (window.location.pathname.endsWith("/delegator")) {
    adminPage.initialize(true);
    new linkerdDtabPlayground();
  } else if (window.location.pathname.endsWith("/logging")) {
    adminPage.initialize();
    new loggingConfig();
  } else {
    adminPage.initialize();
    new dashboard();
  }
});
