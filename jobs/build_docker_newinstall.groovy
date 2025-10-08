import util.Plumber

def p = new Plumber(name: 'sqre/infra/build_docker_newinstall', dsl: this)
p.pipeline().with {
  description('Constructs docker docker-newinstall images.')

  parameters {
    booleanParam('NO_PUSH', false, 'Do not push image to docker registry.')
  }
}
