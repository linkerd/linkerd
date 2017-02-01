var requireConfig = {
  paths: {
    'jQuery': 'lib/jquery.min',
    'lodash': 'lib/lodash.min',
    'Handlebars': 'lib/handlebars-v4.0.5',
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
}

require.config(requireConfig);

require([
  'jQuery',
  'bootstrap',
  'src/dashboard_delegate'
], function ($, bootstrap, namerdDtabPlayground) {
  new namerdDtabPlayground();
});
