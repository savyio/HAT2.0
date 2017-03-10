#!/bin/bash

cd ./bsafe

npm install

ng build -prod

cd ..

cp ./bsafe/dist/inline.*.bundle.js ./hat/app/org/hatdex/hat/phata/assets/js/inline.bundle.js

cp ./bsafe/dist/polyfills.*.bundle.js ./hat/app/org/hatdex/hat/phata/assets/js/polyfills.bundle.js

cp ./bsafe/dist/main.*.bundle.js ./hat/app/org/hatdex/hat/phata/assets/js/main.bundle.js

cp ./bsafe/dist/vendor.*.bundle.js ./hat/app/org/hatdex/hat/phata/assets/js/vendor.bundle.js

cp ./bsafe/dist/styles.*.bundle.css ./hat/app/org/hatdex/hat/phata/assets/stylesheets/styles.bundle.css
