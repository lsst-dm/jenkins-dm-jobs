import util.Plumber

def p = new Plumber(name: 'sqre/infrastructure/build-ltd-mason', dsl: this)
p.pipeline().with {
  description('Constructs docker ltd-mason images.')

  parameters {
    stringParam('LTD_MASON_VER', '0.2.5', 'ltd-mason version to install')
    booleanParam('NO_PUSH', false, 'Do not push image to docker registry.')
    booleanParam('LATEST', false, 'Also push to docker registry with "latest" tag.')
  }
}
