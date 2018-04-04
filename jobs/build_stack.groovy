import util.Plumber

def p = new Plumber(name: 'release/docker/build-stack', dsl: this)
p.pipeline().with {
  description('Constructs docker images with EUPS tarballs.')

  parameters {
    stringParam('PRODUCT', 'lsst_distrib', 'Whitespace delimited list of EUPS products to build.')
    stringParam('TAG', null, 'EUPS distrib tag name. Eg. w_2016_08')
    booleanParam('NO_PUSH', false, 'Do not push image to docker registry.')
    stringParam('TIMEOUT', '1', 'build timeout in hours')
  }

  concurrentBuild(true)
}
