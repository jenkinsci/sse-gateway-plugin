var httpProxy = require('http-proxy');
var port = process.argv[2];
 
httpProxy.createProxyServer({target:'http://localhost:' + port}).listen(18080);
console.log('*** Proxying port 18080 to localhost:' + port);
process.send({started: true});