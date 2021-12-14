import util.Plumber

def p = new Plumber(
  name: 'sqre/infra/build-sciplatlab',
  dsl: this
)
p.pipeline().with {
  description('Constructs Rubin Science Platform Notebook Aspect docker images.')

  parameters {
    stringParam('TAG', null, 'eups distrib tag')
    booleanParam('NO_PUSH', false, 'Do not push image to docker registry.')
    booleanParam('FLATTEN', false, 'Collapse image to single layer.')
    booleanParam('VERBOSE', false, 'Verbose build script output.')
    booleanParam('JLBLEED', false, 'Build bleeding-edge JupyterLab.')
    stringParam('BASE_IMAGE', 'lsstsqre/centos', 'Base Docker image')
    stringParam('IMAGE_NAME', 'lsstsqre/sciplat-lab', 'Output image name')
    stringParam('TAG_PREFIX', '7-stack-lsst_distrib-', 'Tag prefix')
    stringParam('TIMEOUT', '4', 'build timeout in hours')
  }

  concurrentBuild(true)
}
