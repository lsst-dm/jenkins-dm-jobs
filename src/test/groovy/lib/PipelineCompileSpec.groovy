package lib

import spock.lang.Specification
import spock.lang.Unroll

class PipelineCompileSpec extends Specification {
  @Unroll
  def "#path compiles"() {
    expect:
    PipelineScriptLoader.parse(path) != null

    where:
    path << [
      'pipelines/release/nightly_release.groovy',
      'pipelines/release/weekly_release.groovy',
      'pipelines/release/run_rebuild.groovy',
      'pipelines/stack_os_matrix.groovy',
    ]
  }

  // Guards the guard: without this, a parse() that silently swallowed errors
  // would leave the cases above passing for every possible input.
  def "parse rejects a script that does not compile"() {
    given:
    def broken = File.createTempFile('broken', '.groovy')
    broken.deleteOnExit()
    broken.text = 'def unclosed = {\n'

    when:
    PipelineScriptLoader.parse(broken.absolutePath)

    then:
    thrown(Exception)
  }
}
