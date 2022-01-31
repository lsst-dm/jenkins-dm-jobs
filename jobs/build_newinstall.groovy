import util.Plumber

def p = new Plumber(name: 'sqre/infra/build-newinstall', dsl: this)
p.pipeline().with {
  description('Constructs newinstall docker images.')

  parameters {
    booleanParam('NO_PUSH', false, 'Do not push image to docker registry.')
    booleanParam('LATEST', true, 'Also push to docker registry with "latest" tag.')
    stringParam('SPLENV_REF', '', 'rubin-env reference.')
  }
}
