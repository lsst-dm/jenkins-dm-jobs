import util.Plumber

def p = new Plumber(
  name: 'sqre/infra/build-salsciplatlab',
  dsl: this
)
p.pipeline().with {
  description('Constructs docker SAL LSST Science Platform Notebook Aspect images.')

  parameters {
    stringParam('TAG', null, 'eups tag')
    booleanParam('JLBLEED', false, 'Build bleeding-edge image.')
    booleanParam('NO_PUSH', false, 'Do not push image to docker registry.')
    stringParam('BASE_IMAGE', 'lsstsqre/sciplat-lab', 'Base Docker image')
    stringParam('IMAGE_NAME', 'lsstsqre/sal-sciplat-lab', 'Output image name')
    stringParam('TIMEOUT', '3', 'build timeout in hours')
  }

  concurrentBuild(true)
}
