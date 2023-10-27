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
    stringParam('IMAGE', 'us-central1-docker.pkg.dev/rubin-shared-services-71ec/sciplat/sciplat-lab,ghcr.io/lsst-sqre/sciplat-lab,docker.io/lsstsqre/sciplat-lab', 'Fully-qualified URI(s) for Docker target image(s, comma-separated)')
    booleanParam('NO_PUSH', false, 'Do not push image to docker registr(y/ies)')
    stringParam('BRANCH', 'main', 'Branch from which to build image')
  }
}
