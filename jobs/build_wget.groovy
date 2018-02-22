import util.Plumber

def p = new Plumber(name: 'sqre/infrastructure/build-wget', dsl: this)
p.pipeline().with {
  description('Constructs docker wget images.')

  parameters {
    booleanParam('NO_PUSH', false, 'Do not push image to docker registry.')
    booleanParam('LATEST', false, 'Also push to docker registry with "latest" tag.')
  }
}
