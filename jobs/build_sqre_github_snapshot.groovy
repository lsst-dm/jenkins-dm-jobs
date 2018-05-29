import util.Plumber

def p = new Plumber(name: 'sqre/backup/build-sqre-github-snapshot', dsl: this)
p.pipeline().with {
  description('Constructs a docker image to run sqre-github-snapshot.')

  parameters {
    booleanParam('NO_PUSH', false, 'Do not push image to docker registry.')
    booleanParam('LATEST', true, 'Also push to docker registry with "latest" tag.')
  }
}
