import util.Plumber

def p = new Plumber(name: 'sqre/infra/build_docker_newinstall', dsl: this)
p.pipeline().with {
  description('Constructs docker docker-newinstall images.')

  parameters {
    stringParam('SPLENV_REF', "12.0.0", 'Set which rubin env to build image with.')
    booleanParam('LATEST', true, 'Should we add the latest to the tags of the image')
    booleanParam('NO_PUSH', false, 'Do not push image to docker registry.')
  }
}
