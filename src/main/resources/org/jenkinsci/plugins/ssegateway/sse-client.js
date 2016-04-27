if (window.EventSource) {
    var source = new EventSource('./listen');

    source.addEventListener('message', function (e) {
        console.log(e.data);
    }, false);

    source.addEventListener('open', function (e) {
        console.log('Connection opened.');
        // Connection was opened.
    }, false);

    source.addEventListener('error', function (e) {
        if (e.readyState == EventSource.CLOSED) {
            // Connection was closed.
        }
    }, false);
} else {
    console.error();
}