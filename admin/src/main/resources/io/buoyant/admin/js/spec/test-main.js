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
    'jQuery': 'lib/jquery-3.1.1.min',
    'lodash': 'lib/lodash.min',
    'handlebars.runtime': 'lib/handlebars.runtime',
    'bootstrap': 'lib/bootstrap.min',
    'Smoothie': 'lib/smoothie',
    'text': 'lib/text'
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
    }
  },

  // dynamically load all test files
  deps: allTestFiles,

  // we have to kickoff jasmine, as it is asynchronous
  callback: window.__karma__.start
})
