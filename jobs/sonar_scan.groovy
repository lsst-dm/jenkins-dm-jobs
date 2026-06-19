import util.Plumber

def p = new Plumber(
  name: 'sqre/infra/sonar-scan',
  dsl: this
)
p.pipeline().with {
  description('Run SonarQube static analysis against the lsstsw cache produced by a weekly or nightly release.')

  parameters {
    stringParam('EUPS_TAG', null, 'EUPS tag this scan represents, e.g. w_2026_21 or d_2026_05_29. Used as sonar.projectVersion.')
    stringParam('CACHE_TAG', 'd_latest', 'Tag suffix of the lsstsw cache tarball in gs://eups-lsstsw-cache.')
  }
}
