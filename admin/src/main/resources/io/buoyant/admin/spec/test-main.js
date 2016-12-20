var allTestFiles = []
var TEST_REGEXP = /(spec|test)\.js$/i

// Get a list of all the test files to include
Object.keys(window.__karma__.files).forEach(function (file) {
  if (TEST_REGEXP.test(file)) {
    // Normalize paths to RequireJS module names.
    // If you require sub-dependencies of test files to be loaded as-is (requiring file extension)
    // then do not normalize the paths
    var normalizedTestModule = file.replace(/^\/base\/|\.js$/g, '')
    allTestFiles.push(normalizedTestModule)
  }
})

require.config({
  // Karma serves files under /base, which is the basePath from your config file
  baseUrl: '/base',

  // grab this from our app's main.js, but make sure the paths are correct
  // relative to karma.conf.js's perspective
  paths: {
    'jQuery': 'js/lib/jquery.min',
    'lodash': 'js/lib/lodash.min',
    'Handlebars': 'js/lib/handlebars-v4.0.5',
    'bootstrap': 'js/lib/bootstrap.min',
    'SmoothieChart': 'js/lib/smoothie',
    'TimeSeries': 'js/lib/smoothie_copy'
  },
  // grab this from our app's main.js, but make sure the paths are correct
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
  },

  // dynamically load all test files
  deps: allTestFiles,

  // we have to kickoff jasmine, as it is asynchronous
  callback: window.__karma__.start
})
