module.exports = function(grunt) {
  grunt.initConfig({
    requirejs: {
      // !! You can drop your app.build.js config wholesale into 'options'
      linkerd: {
        options: {
          baseUrl: "./js",
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
          },
          name: "main-linkerd",
          mainConfigFile: 'js/main-linkerd.js',
          out: "js/out/main-linkerd-built.js",
          include: ["requireLib"]
        }
      },
      namerd: {
        options: {
          baseUrl: "./js",
          paths: {
            requireLib: 'lib/require',
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
          },
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
