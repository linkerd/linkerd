## Javascript

This app uses RequireJS to load modules. The config is in `/js/main.js`.
App code goes in `/js/src` and vendor code in `/js/lib`.

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
npm test # karma start
```

To run eslint:
```
cd /linkerd/admin/src/main/resources/io/buoyant/admin
npm run eslint # eslint js
```

If you're writing js, you may find it useful to automatically rerun the tests
when a file changes:
```
karma start --autoWatch=true --singleRun=false
```

## Building JS

We use the requirejs optimizer, r.js, to speed up our app.

```
cd /linkerd/admin/src/main/resources/io/buoyant/admin
node js/lib/r.js -o js/linkerd.build.js
```
