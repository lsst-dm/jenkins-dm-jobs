import util.Plumber

def p = new Plumber(name: 'sqre/infra/build-layercake', dsl: this)
p.pipeline().with {
  description('Constructs stack of docker base images for scipipe releases.')

  parameters {
    booleanParam('NO_PUSH', false, 'Do not push image to docker registry.')
  }
}
