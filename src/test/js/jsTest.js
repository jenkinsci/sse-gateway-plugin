var jsdom = require("jsdom");

var JENKINS_URL_REGEX = new RegExp('@JENKINS_URL@', 'g');

exports.onPage = function (testFunc, content) {
    
    throw(new Error('Earth-shattering kaboom'));
    
    if (!content) {
        content = '<html><head data-rooturl="@JENKINS_URL@" data-resurl="@JENKINS_URL@/static/908d75c1" data-adjuncturl="@JENKINS_URL@/adjuncts/908d75c1"></head><body></body></html>';
        if (process.env.JENKINS_URL) {
            content = content.replace(JENKINS_URL_REGEX, process.env.JENKINS_URL);
        } else {
            content = content.replace(JENKINS_URL_REGEX, '/jenkins');
        }
    }
    jsdom.env(content, [],
        function (errors, window) {
            if (!window.navigator) {
                window.navigator = {
                    userAgent: 'JasmineTest'
                };
            }
            global.window = window;
            global.document = window.document;
            global.navigator = window.navigator;

            testFunc();
        }
    );
};
