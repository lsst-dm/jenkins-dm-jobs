import util.Plumber

def p = new Plumber(name: 'sqre/infra/build-docker-scipipe-base', dsl: this)
p.pipeline().with {
  description('Constructs the docker-scipipe AlmaLinux 9 base image (amd64 + arm64) that docker-newinstall builds on.')

  parameters {
    booleanParam('NO_PUSH', false, 'Do not push image to docker registry.')
    booleanParam('NO_CACHE', true, 'Disable the BuildKit layer cache so every layer is rebuilt.')
  }
}
