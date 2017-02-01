module.exports = function(grunt) {
  grunt.initConfig({
    /* ... */

    // !! This is the name of the task ('requirejs')
    requirejs: {
      compile: {

        // !! You can drop your app.build.js config wholesale into 'options'
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
          out: "js/out/main-built.js",
          include: ["requireLib"]
        }
      }
    },
    karma: {
      unit: {
          configFile: 'karma.conf.js'
      }
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

  // !! This loads the plugin into grunt
  grunt.loadNpmTasks('grunt-contrib-requirejs');
  grunt.loadNpmTasks('grunt-contrib-watch');
  grunt.loadNpmTasks('grunt-karma');

};
