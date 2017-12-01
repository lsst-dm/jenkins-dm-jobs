import util.Plumber

def p = new Plumber(name: 'sqre/infrastructurebuild-jupyterlabdemo', dsl: this)
p.pipeline().with {
  description('Constructs docker jupyterlabdemo images.')

  parameters {
    stringParam('TAG', null, 'eups distrib tag')
    booleanParam('NO_PUSH', false, 'Do not push image to docker registry.')
    stringParam('PYVER', '3', 'Python version')
    stringParam('BASE_IMAGE', 'lsstsqre/centos', 'Base Docker image')
    stringParam('IMAGE_NAME', 'lsstsqre/jld-lab', 'Output image name')
    stringParam('TAG_PREFIX', '7-stack-lsst_distrib-', 'Tag prefix')
    stringParam('TIMEOUT', '1', 'build timeout in hours')
  }

  concurrentBuild(true)
}
