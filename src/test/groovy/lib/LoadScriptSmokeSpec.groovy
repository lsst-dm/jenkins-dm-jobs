package lib

import spock.lang.Specification

class LoadScriptSmokeSpec extends Specification {
  def "util.groovy loads without JPU and pure methods run"() {
    when:
    def util = PipelineScriptLoader.load('pipelines/lib/util.groovy')

    then:
    util != null
    util.dedent("\n    foo\n    bar\n") == "foo\nbar\n"
    util.buildkitCacheArgs('repo/x', 'amd64').contains('--cache-from type=registry,ref=repo/x:cache-amd64')
  }
}
