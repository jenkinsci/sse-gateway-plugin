Changelog
===

### Newer versions

See [GitHub releases](https://github.com/jenkinsci/sse-gateway-plugin/releases)

## Version 1.20 (Aug 29, 2019)

-   [JENKINS-58684](https://issues.jenkins-ci.org/browse/JENKINS-58684):
    use a executor to run the retry and avoid too many threads. Improve errors logging.

## Version 1.19 (Aug 2, 2019)

-   [JENKINS-58684](https://issues.jenkins-ci.org/browse/JENKINS-58684):
    Give meaningful names to threads created by the plugin  (to diagnose the issue) 

## Version 1.18 (Jun 26, 2019)

-   [JENKINS-51057](https://issues.jenkins-ci.org/browse/JENKINS-51057):
    Fix memory leak of EventDispatcher 

## Version 1.17 (Dec 28, 2018)

-   [JENKINS-54099](https://issues.jenkins-ci.org/browse/JENKINS-54099):
    Reduce logs verbosity

## Version 1.16 (Jan 26, 2017)

-   [JENKINS-52123](https://issues.jenkins-ci.org/browse/JENKINS-52123): Adjust
    sse-gateway to the now configurable location for tasks logging

## Version 1.15 (Jan 26, 2017)

-   [JENKINS-41487](https://issues.jenkins-ci.org/browse/JENKINS-41487):
    new release of pubsub-light because it wasn't in the update centre.

## Version 1.14 (Jan 26, 2017)

-   Updated to use the pubsub-light plugin (used to be a
    jenkins-module).

## Version 1.13 (Jan 23, 2017)

-   Ping endpoint for
    [JENKINS-40326](https://issues.jenkins-ci.org/browse/JENKINS-40326).

## Version 1.12 (Jan 13, 2017)

-   [JENKINS-39894](https://issues.jenkins-ci.org/browse/JENKINS-39894):
    Changed Event polyfill hoping to fix MSIE intermittent message loss.
-   [JENKINS-41063](https://issues.jenkins-ci.org/browse/JENKINS-41063): Make
    EventDispatcher instances Serializable.

## Version 1.11 (Jan 9, 2017)

-   paused and unpaused states for
    [JENKINS-40648](https://issues.jenkins-ci.org/browse/JENKINS-40648).

## Version 1.10 (Sept 24, 2016)

-   Asynchronous configuration in an attempt to
    address [JENKINS-38252](https://issues.jenkins-ci.org/browse/JENKINS-38252).

## Version 1.9 (Sept 12, 2016)

-   Misc fixes to get the ATH running better

**Note**: This version of the plugin must be used with version [0.0.9
(and above) of the NPM
package](https://www.npmjs.com/package/@jenkins-cd/sse-gateway).

## Version 1.8 (Aug 3, 2016)

-   [JENKINS-36829](https://issues.jenkins-ci.org/browse/JENKINS-36829):
    Jenkins root URL fix

## Version 1.7 (Aug 2, 2016)

-   Regression fix for the Acceptance Test Harness to maintain sessions.

## Version 1.6 (July 29, 2016)

-   [JENKINS-36238](https://issues.jenkins-ci.org/browse/JENKINS-36238): Server-side
    store and forward of SSE events on client disconnect

**Note**: This version of the plugin must be used with version [0.0.7
(and above) of the NPM
package](https://www.npmjs.com/package/@jenkins-cd/sse-gateway).

## Version 1.5 (July 19, 2016)

-   [JENKINS-36704](https://issues.jenkins-ci.org/browse/JENKINS-36704): Manageable
    client-side logging
-   [JENKINS-36763](https://issues.jenkins-ci.org/browse/JENKINS-36763): SSE
    client API disconnect not releasing channel listeners

**Note**: This version of the plugin must be used with version [0.0.6
(and above) of the NPM
package](https://www.npmjs.com/package/@jenkins-cd/sse-gateway).

## Version 1.4 (June 29, 2016)

-   JENKINS-36218: Pubsub event enrichment @ExtensionPoint

## Version 1.3 (June 15, 2016)

-   JENKINS-35711: Fixed pubsub-light-module to handle ParameterizedJob
    (instead of Job)

## Version 1.2 (June 2, 2016)

-   Easier headless client

## Version 1.1 (May 26, 2016)

-   JENKINS-35137: EventSource reconnect killing old EvenDispatcher and
    losing channel subs

## Version 1.0 (May 24, 2016)

-   Initial Release
