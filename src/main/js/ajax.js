var json = require('./json');

exports.get = function (url, onSuccess) {
    var http = new XMLHttpRequest();

    http.onreadystatechange = function () {
        if (http.readyState === 4) {
            if (http.status >= 200 && http.status < 300) {
                try {
                    var responseJSON = JSON.parse(http.responseText);
                    // The request may have succeeded, but there might have been
                    // some processing error on the backend and a hudson.util.HttpResponses
                    // JSON response.
                    if (responseJSON.status && responseJSON.status === 'error') {
                        console.error('SSE Gateway error response to '
                            + url + ': '
                            + responseJSON.message);
                    }

                    if (onSuccess) {
                        onSuccess(responseJSON);
                    }
                } catch (e) {
                    // Not a JSON response.
                }
            } else {
                console.error('SSE Gateway error to ' + url + ': ');
                console.error(http);
            }
        }
    };

    http.open('GET', url, true);

    http.setRequestHeader('Accept', 'application/json');
    http.send();
};

exports.post = function (data, toUrl, jenkinsSessionInfo) {
    var http = new XMLHttpRequest();

    http.onreadystatechange = function () {
        if (http.readyState === 4) {
            if (http.status >= 200 && http.status < 300) {
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
                console.error('SSE Gateway error to ' + toUrl + ': ');
                console.error(http);
            }
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

    if (jenkinsSessionInfo.crumb
        && jenkinsSessionInfo.crumb.name
        && jenkinsSessionInfo.crumb.value) {
        http.setRequestHeader(jenkinsSessionInfo.crumb.name, jenkinsSessionInfo.crumb.value);
    }

    if (data) {
        if (typeof data === 'object') {
            http.send(json.stringify(data));
        } else {
            http.send(data);
        }
    } else {
        http.send();
    }
};
