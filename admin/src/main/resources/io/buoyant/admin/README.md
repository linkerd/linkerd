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
grunt karma # OR npm test OR karma start
```

To run eslint:
```
cd /linkerd/admin/src/main/resources/io/buoyant/admin
grunt eslint # OR npm run eslint OR eslint js
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

## Development

We use grunt as our task manager, and `r.js` to optimize our require.


To develop, run `grunt watch` which will watch for file changes and re-run the
r.js optimizer to produce our bundled js.

If you don't want to wait 10s every time you hit save on a file, go to
AdminHandler.scala and replace this line

```
<script src="files/js/out/main-built.js"></script>
```

with this line

```
<script data-main="files/js/main-linkerd" src="files/js/lib/require.js"></script>
```

## Release

To release, run `grunt release` which will run eslint, run the tests and run
the r.js optimizer to gather our js and templates into a single uglified file.
