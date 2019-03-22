require.config({
  paths: {
    'jQuery': 'lib/jquery-3.1.1.min',
    'lodash': 'lib/lodash.min',
    'handlebars.runtime': 'lib/handlebars.runtime',
    'bootstrap': 'lib/bootstrap.min',
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
  'bootstrap',
  'src/dashboard_delegate',
  'src/logging'
], function ($, bootstrap, namerdDtabPlayground, loggingConfig) {
  if(window.location.pathname.indexOf("/dtab") >= 0){
    new namerdDtabPlayground();
  } else if(window.location.pathname.endsWith("/logging")){
    new loggingConfig();
  }
});
