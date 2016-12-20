## JS

This app uses RequireJS to load modules. The config is in `/js/main.js`.
App code goes in `/js/app` and vendor code in `/js/lib`.

## Tests

The tests are written using [Jasmine](https://jasmine.github.io/) and run in
node. Becuase our modules are AMD,
we need `amdefine` to access them in node environments. To use a module in the
tests, add
```
if (typeof define !== 'function') { var define = require('amdefine')(module) }
```
at the top of the module.

If you'd like to run eslint or jasmine locally, first install the necessary modules:
```
cd /linkerd/admin/src/main/resources/io/buoyant/admin
npm install
```

To run the tests:
```
cd /linkerd/admin/src/main/resources/io/buoyant/admin
jasmine // OR npm test
```

To run eslint:
```
cd /linkerd/admin/src/main/resources/io/buoyant/admin
eslint js // OR npm run eslint
```