import util.Plumber

def p = new Plumber(
  name: 'sqre/infra/build-documenteer-base',
  dsl: this
)
p.pipeline().with {
  description('Constructs docker images.')

  parameters {
    booleanParam('NO_PUSH', false, 'Do not push image to docker registry.')
    booleanParam('LATEST', true, 'Also push to docker registry with "latest" tag.')
  }

  triggers {
    upstream('sqre/infra/build-newinstall', 'SUCCESS')
  }
}
