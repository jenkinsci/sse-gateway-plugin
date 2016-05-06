
exports.post = function(data, toUrl, jenkinsSessionInfo) {
    var http = new XMLHttpRequest();
    http.open("POST", toUrl, true);

    http.setRequestHeader("Content-type", "application/json");
    if (http.setDisableHeaderCheck) {
        // This is a test !!
        // XMLHttpRequest is coming from the xmlhttprequest npm package.
        // It allows us to turn off the W3C spec header checks, allowing us to set
        // the cookie and so maintain the session for the test (not running in a browser).
        // TODO: Make sure the browsers XMLHttpRequest does maintain the session. I'm not convinced it does !!
        http.setDisableHeaderCheck(true);
        http.setRequestHeader("Cookie", jenkinsSessionInfo.cookieName + "=" + jenkinsSessionInfo.sessionid);
    }
    
    // Jenkins POSTs require this crumb to be set. 
    if (jenkinsSessionInfo.crumb && jenkinsSessionInfo.crumb.name && jenkinsSessionInfo.crumb.value) {
        http.setRequestHeader(jenkinsSessionInfo.crumb.name, jenkinsSessionInfo.crumb.value);
    }
    
    if (data) {
        if (typeof data === 'object') {
            data = JSON.stringify(data);
        }

        http.send(data);
    } else {
        http.send();
    }
};