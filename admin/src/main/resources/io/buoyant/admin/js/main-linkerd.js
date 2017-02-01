require([
  'jQuery',
  'lodash',
  'bootstrap',
  'src/admin',
  'src/dashboard',
  'src/delegate',
  'src/namerd',
  'src/logging'
], function (
  $, _, bootstrap,
  adminPage,
  dashboard,
  linkerdDtabPlayground,
  namerd,
  loggingConfig
) {
  // poor man's routing
  if (window.location.pathname.endsWith("/delegator")) {
    adminPage.initialize(true).done(function() {
      new linkerdDtabPlayground();
    });
  } else if (window.location.pathname.endsWith("/namerd")) {
    adminPage.initialize().done(function() {
      new namerd();
    });
  } else if (window.location.pathname.endsWith("/logging")) {
    adminPage.initialize().done(function() {
      new loggingConfig();
    });
  } else if (window.location.pathname.endsWith("/help")) {
      return;
  } else {
    adminPage.initialize().done(function(routerConfig) {
      new dashboard(routerConfig);
    });
  }
});
