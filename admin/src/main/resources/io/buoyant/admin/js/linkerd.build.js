({
    // baseUrl: "./js",
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
    mainConfigFile: 'main-linkerd.js',
    out: "out/main-built.js",
    include: ["requireLib"]
})
