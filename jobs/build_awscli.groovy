import util.Plumber

def p = new Plumber(name: 'sqre/infrastructure/build-awscli', dsl: this)
p.pipeline().with {
  description('Constructs docker awscli images.')

  parameters {
    stringParam('AWSCLI_VER', '1.14.2', 'awscli version to install')
    booleanParam('NO_PUSH', false, 'Do not push image to docker registry.')
    booleanParam('LATEST', false, 'Also push to docker registry with "latest" tag.')
  }
}
