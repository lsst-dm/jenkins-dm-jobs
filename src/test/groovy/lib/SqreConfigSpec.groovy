package lib

import org.yaml.snakeyaml.Yaml
import spock.lang.Specification

/**
 * etc/sqre/config.yaml is read at runtime by the pipelines; a missing key only
 * shows up as a null dereference on an agent. Pin the keys the daily image
 * build depends on.
 */
class SqreConfigSpec extends Specification {
  static Object loadYaml(String path) {
    new Yaml().load(new File(path).text)
  }

  def sqre = loadYaml('etc/sqre/config.yaml')

  def "scipipe_base has the keys build-docker-scipipe-base reads"() {
    expect:
    sqre.scipipe_base.dockerfile.github_repo == 'lsst-dm/docker-scipipe'
    sqre.scipipe_base.dockerfile.git_ref
    sqre.scipipe_base.dockerfile.dir == 'base-9'
    sqre.scipipe_base.docker_registry.repo
    sqre.scipipe_base.docker_registry.tag
  }

  def "scipipe_base builds the image the build matrix consumes"() {
    given:
    def scipipe = loadYaml('etc/scipipe/build_matrix.yaml')
    def built = "${sqre.scipipe_base.docker_registry.repo}:${sqre.scipipe_base.docker_registry.tag}"

    expect:
    scipipe.build_stack.lsstsw_config*.image.every { it == built.toString() }
  }
}
