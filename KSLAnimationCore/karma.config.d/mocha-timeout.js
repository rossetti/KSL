// Mocha's default 2 s per-test timeout is charged, for whichever test runs first, with headless
// Chrome's cold start: loading the bundle and initialising the serializers. That made the first
// conformance test fail on a fresh browser while taking milliseconds itself. Ten seconds absorbs
// the start-up; a genuinely hung test still fails.
config.set({
    client: {
        mocha: {
            timeout: 10000
        }
    }
});
