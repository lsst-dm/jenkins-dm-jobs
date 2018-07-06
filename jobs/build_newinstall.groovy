import util.Plumber

def p = new Plumber(name: 'sqre/infra/build-newinstall', dsl: this)
p.pipeline().with {
  description('Constructs newinstall docker images.')

  parameters {
    booleanParam('NO_PUSH', false, 'Do not push image to docker registry.')
    booleanParam('LATEST', true, 'Also push to docker registry with "latest" tag.')
  }

  // it would be slick if we could trigger based on dockerhub lsstsqre/centos
  // notifications but AFAIK, this can't be restricted by tag
  triggers {
    upstream('sqre/infra/build-layercake', 'SUCCESS')
  }
}
