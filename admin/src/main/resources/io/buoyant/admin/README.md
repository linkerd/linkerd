## JS

This app uses RequireJS to load modules. The config is in `/js/main.js`.
App code goes in `/js/app` and vendor code in `/js/lib`.

## Tests

The tests are written using [Jasmine](https://jasmine.github.io/) and run by
[Karma](https://karma-runner.github.io).

If you'd like to run eslint or tests locally, first install the necessary
node modules:
```
cd /linkerd/admin/src/main/resources/io/buoyant/admin
npm install
```

To run the tests:
```
cd /linkerd/admin/src/main/resources/io/buoyant/admin
karma start
```

To run eslint:
```
cd /linkerd/admin/src/main/resources/io/buoyant/admin
eslint js // OR npm run eslint
```