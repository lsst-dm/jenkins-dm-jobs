import util.Plumber
import org.yaml.snakeyaml.Yaml

def scipipe = new Yaml().load(readFileFromWorkspace('etc/scipipe/build_matrix.yaml'))

def p = new Plumber(name: 'release/docker/build-stack', dsl: this)
p.pipeline().with {
  description('Constructs docker images with EUPS tarballs.')

  parameters {
    stringParam('PRODUCTS', scipipe.canonical.products,
      'Whitespace delimited list of EUPS products to build.')
    stringParam('TAG', null, 'EUPS distrib tag name. Eg. w_2016_08')
    booleanParam('NO_PUSH', false, 'Do not push image to docker registry.')
    stringParam('TIMEOUT', '1', 'build timeout in hours')
    stringParam('DOCKER_TAGS', null, 'Optional whitespace delimited list of additional docker image tags.')
  }

  concurrentBuild(true)
}
