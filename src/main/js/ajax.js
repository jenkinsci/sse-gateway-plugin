var json = require('./json');

exports.post = function (data, toUrl, jenkinsSessionInfo) {
    // Jenkins POSTs require this crumb to be set.
    if (jenkinsSessionInfo.crumb
        && jenkinsSessionInfo.crumb.name
        && jenkinsSessionInfo.crumb.value) {
        var http = new XMLHttpRequest();

        http.onreadystatechange = function () {
            if (http.readyState === 4 && http.status === 200) {
                try {
                    var responseJSON = JSON.parse(http.responseText);
                    // The request may have succeeded, but there might have been
                    // some processing error on the backend and a hudson.util.HttpResponses
                    // JSON response.
                    if (responseJSON.status && responseJSON.status === 'error') {
                        console.error('SSE Gateway error response to '
                            + toUrl + ': '
                            + responseJSON.message);
                    }
                } catch (e) {
                    // Not a JSON response.
                }
            } else {
                console.error('SSE Gateway error: ');
                console.error(http);
            }
        };

        http.open('POST', toUrl, true);

        http.setRequestHeader('Content-type', 'application/json');
        if (http.setDisableHeaderCheck
            && jenkinsSessionInfo.cookieName
            && jenkinsSessionInfo.sessionid) {
            // This is a test !!
            // XMLHttpRequest is coming from the xmlhttprequest npm package.
            // It allows us to turn off the W3C spec header checks, allowing us to set
            // the cookie and so maintain the session for the test (not running in a browser).
            // TODO: Make sure the browsers XMLHttpRequest does maintain the session.
            // I'm not convinced it does !!
            http.setDisableHeaderCheck(true);
            http.setRequestHeader('Cookie', jenkinsSessionInfo.cookieName
                + '=' + jenkinsSessionInfo.sessionid);
        }

        http.setRequestHeader(jenkinsSessionInfo.crumb.name, jenkinsSessionInfo.crumb.value);

        if (data) {
            if (typeof data === 'object') {
                http.send(json.stringify(data));
            } else {
                http.send(data);
            }
        } else {
            http.send();
        }
    } else {
        console.warn('Cannot connect to Jenkins SSE Gateway. No Crumb.');
    }
};
