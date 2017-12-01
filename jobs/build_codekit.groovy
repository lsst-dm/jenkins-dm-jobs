import util.Plumber

def p = new Plumber(name: 'sqre/infrastructure/build-codekit', dsl: this)
p.pipeline().with {
  description('Constructs docker sqre-codekit images.')

  parameters {
    stringParam('CODEKIT_VER', '3.1.0', 'sqre-codekit pypi version to install')
    booleanParam('NO_PUSH', false, 'Do not push image to docker registry.')
    booleanParam('LATEST', false, 'Also push to docker registry with "latest" tag.')
  }
}
