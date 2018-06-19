import util.Plumber

def p = new Plumber(name: 'sqre/infrastructure/build-postqa', dsl: this)
p.pipeline().with {
  description('Constructs docker postqa images.')

  parameters {
    booleanParam('NO_PUSH', false, 'Do not push image to docker registry.')
    booleanParam('LATEST', false, 'Also push to docker registry with "latest" tag.')
  }
}
