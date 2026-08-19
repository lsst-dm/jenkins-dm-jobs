# Unit tests for pipeline helpers

`pipelines/lib/util.groovy` and `pipelines/lib/notify.groovy` contain a lot of
pure logic: rendering pod YAML, sanitising eups and docker tags, assembling
buildkit cache arguments, parsing image labels. Historically the only way to
find out whether any of it was correct was to run a real Jenkins job and read
the console log.

This directory lets those helpers run on a plain JVM, so a mistake shows up in
seconds instead of after a pod has been scheduled.

## Running the tests

```bash
./gradlew test
```

To force a re-run when Gradle has cached the task as up-to-date:

```bash
./gradlew test --rerun-tasks
```

Gradle prints no per-test output on success. For a specific spec, or to see the
individual test names:

```bash
./gradlew test --tests 'lib.UtilHelpersSpec' -i
```

An HTML report is written to `build/reports/tests/test/index.html`, and the raw
JUnit XML to `build/test-results/test/`.

## Requirements

- **Java 8.** Gradle 8.7 (via `./gradlew`) and Groovy 3.0.21 both run here, and
  Java 8 is what the SLAC build hosts provide. Groovy 3 and Spock 2.3 are not
  reliable on recent JDKs, so if you have a newer default JDK, point
  `JAVA_HOME` at a Java 8 or 11 installation before running the suite. Nothing
  in `build.gradle` pins a toolchain yet, so the JVM on your `PATH` decides.
- **Network access on the first run.** The wrapper downloads Gradle itself, and
  the test dependencies come from Maven Central plus
  <https://repo.jenkins-ci.org/releases/>. Subsequent runs work from the Gradle
  cache.

You do not need to run `./gradlew libs` first; that task only stages
`snakeyaml` into `lib/` for the seed job and is unrelated to the tests.

## How the harness works

Jenkins pipeline scripts are not classes. They are Groovy scripts that call
steps (`sh`, `node`, `echo`, `withCredentials`) which only exist inside a
running Jenkins, and they end by returning `this` so a `load` step can hand
back their methods. Loading one outside Jenkins therefore fails on the first
step call.

The usual answer is JenkinsPipelineUnit, which is already declared in
`build.gradle` but deliberately unused: it requires Java 9 or later. Three
small pieces stand in for it.

- `groovy/lib/StepSwallowingScript.groovy` — base script whose
  `methodMissing` and `propertyMissing` return `null`, so undefined pipeline
  steps evaluate as harmless no-ops.
- `groovy/lib/PipelineScriptLoader.groovy` — parses a pipeline script with that
  base class and an import of the `@NonCPS` stub, runs it, and returns the
  object the script yields.
- `groovy/com/cloudbees/groovy/cps/NonCPS.groovy` — test-only annotation stub
  so `util.groovy`'s `@NonCPS` annotations resolve off-Jenkins.

The consequence worth remembering: **anything reached through a swallowed step
is invisible to these tests.** A helper whose result depends on what `sh`
returned will see `null`. That is why the specs target pure functions.

## What is covered

- `LoadScriptSmokeSpec` — `util.groovy` loads at all and its pure methods run.
  Fails first and loudest if the harness or the script's top level breaks.
- `UtilHelpersSpec` — `renderPodYaml` (compute classes, arm scheduling,
  hyperdisk vs emptyDir workspaces, sidecars), `buildkitCacheArgs`,
  `sanitizeEupsTag`, `sanitizeDockerTag`, `joinPath`, `dedent`, `shebangerize`,
  the config accessors, and `parseImageLabels`.
- `NotifyThreadContextSpec` — `notify.groovy`'s `addThreadContext`, which
  decides whether a Slack message is threaded and broadcast.

## Adding a spec

Create `src/test/groovy/lib/MyThingSpec.groovy`:

```groovy
package lib

import spock.lang.Specification

class MyThingSpec extends Specification {
  def util = PipelineScriptLoader.load('pipelines/lib/util.groovy')

  def "sanitizeEupsTag prefixes numeric tags with v"() {
    expect:
    util.sanitizeEupsTag('1.2.3') == 'v1_2_3'
  }
}
```

Two things to know:

1. **Paths are relative to the repository root.** Gradle runs tests with the
   project directory as the working directory, so `pipelines/lib/util.groovy`
   resolves. If you run a spec from an IDE, set the working directory to the
   repo root or the load will fail.
2. **To assert on a method that calls other steps**, stub them through the
   loaded object's `metaClass` and restore it afterwards:

   ```groovy
   given:
   Map captured = null
   util.metaClass.insideK8sContainer = { Map p, Closure body -> captured = p }

   when:
   util.insideCodekit { }

   then:
   captured.storage == '2Gi'

   cleanup:
   util.metaClass = null
   ```

   Without the `cleanup`, the stub leaks into later specs in the same class.

## Not yet wired up

These tests do not run in CI. The GitHub Actions workflows in this repo cover
markdown, YAML, and shell only, so nothing currently fails a pull request when
a spec breaks — you have to run `./gradlew test` yourself. Adding a workflow
that does it, and pinning a Java toolchain so the result does not depend on
each developer's default JDK, are both still open.
