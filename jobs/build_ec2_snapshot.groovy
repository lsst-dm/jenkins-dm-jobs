import util.Plumber

def p = new Plumber(name: 'sqre/backup/build-ec2-snapshot', dsl: this)
p.pipeline().with {
  description('Constructs docker image.')

  parameters {
    booleanParam('NO_PUSH', false, 'Do not push image to docker registry.')
    booleanParam('LATEST', false, 'Also push to docker registry with "latest" tag.')
  }
}
