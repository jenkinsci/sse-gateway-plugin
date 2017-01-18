var builder = require('@jenkins-cd/js-builder');

//
// Bundle the modules.
// See https://github.com/jenkinsci/js-builder
//
builder.bundle('src/main/js/sse-gateway-sample.js').less('src/main/js/sse-gateway-sample.less');