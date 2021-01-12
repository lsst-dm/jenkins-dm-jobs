import util.Plumber

def p = new Plumber(name: 'sqre/infra/test-build-newinstall', dsl: this)
p.pipeline().with {
  description('Constructs newinstall docker images. (TEST)')

  parameters {
    booleanParam('NO_PUSH', false, 'Do not push image to docker registry.')
    booleanParam('LATEST', true, 'Also push to docker registry with "latest" tag.')
  }
}
