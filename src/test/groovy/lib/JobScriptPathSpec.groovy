package lib

import spock.lang.Specification
import spock.lang.Unroll

/**
 * Every Plumber-based job derives its pipeline scriptPath from its name
 * (jobs/util/Plumber.groovy#script). A job whose pipeline was deleted is
 * still seeded into Jenkins and fails on checkout, so assert the file exists.
 */
class JobScriptPathSpec extends Specification {
  static final def NAME_PATTERN = /(?s)new Plumber\s*\(.*?name:\s*'([^']+)'/

  static List<List<String>> plumberJobs() {
    new File('jobs').listFiles()
      .findAll { it.name.endsWith('.groovy') }
      .sort { it.name }
      .collect { f ->
        def m = f.text =~ NAME_PATTERN
        m ? [f.name, m[0][1]] : null
      }
      .findAll()
  }

  @Unroll
  def "#job pipeline #name exists"() {
    expect:
    new File("pipelines/${name.tr('-', '_')}.groovy").isFile()

    where:
    [job, name] << plumberJobs()
  }

  def "every Plumber job is discovered"() {
    expect:
    plumberJobs().size() == new File('jobs').listFiles().count {
      it.name.endsWith('.groovy') && it.text.contains('new Plumber')
    }
  }
}
