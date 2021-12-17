import util.Plumber

def p = new Plumber(
  name: 'sqre/infra/build-sciplatlab',
  dsl: this
)
p.pipeline().with {
  description('Constructs Rubin Science Platform Notebook Aspect docker images.')

  parameters {
    stringParam('TAG', null, 'eups distrib tag')
    stringParam('SUPPLEMENTARY', null, 'Supplementary tag for experimental builds')
    stringParam('IMAGE', 'docker.io/lsstsqre/sciplat-lab', 'Fully-qualified URI for Docker image')
    booleanParam('NO_PUSH', false, 'Do not push image to docker registry')
    stringParam('BRANCH', 'prod', 'Branch from which to build image')
  }

  concurrentBuild(true)
}
