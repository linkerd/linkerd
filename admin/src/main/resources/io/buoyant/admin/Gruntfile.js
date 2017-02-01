module.exports = function(grunt) {
  var requireJsConfig = {
    paths: {
      requireLib: 'lib/require',
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
  }

  grunt.initConfig({
    requirejs: {
      linkerd: {
        options: {
          baseUrl: "./js",
          paths: requireJsConfig.paths,
          shim: requireJsConfig.shim,
          name: "main-linkerd",
          mainConfigFile: 'js/main-linkerd.js',
          out: "js/out/main-linkerd-built.js",
          include: ["requireLib"]
        }
      },
      namerd: {
        options: {
          baseUrl: "./js",
          paths: requireJsConfig.paths,
          shim: requireJsConfig.shim,
          name: "main-namerd",
          mainConfigFile: 'js/main-namerd.js',
          out: "js/out/main-namerd-built.js",
          include: ["requireLib"]
        }
      }
    },
    karma: {
      unit: {
          configFile: 'karma.conf.js'
      }
    },
    eslint: {
      options: {
        configFile: '.eslintrc.json'
      },
      target: ['js/*.js', 'js/src/*.js']
    },
    watch: {
      scripts: {
        files: ['js/*.js', 'js/src/*.js'],
        tasks: ['requirejs'],
        options: {
          spawn: false,
        },
      },
    }
  });

  grunt.registerTask('release', ['eslint', 'karma', 'requirejs']);

  grunt.loadNpmTasks('grunt-contrib-requirejs');
  grunt.loadNpmTasks('grunt-contrib-watch');
  grunt.loadNpmTasks('grunt-karma');
  grunt.loadNpmTasks('grunt-eslint');
};
