import util.Plumber

def p = new Plumber(name: 'sqre/infra/test-build-newinstall', dsl: this)
p.pipeline().with {
  description('Constructs newinstall docker images. (TEST)')

  parameters {
    booleanParam('NO_PUSH', false, 'Do not push image to docker registry.')
    booleanParam('LATEST', true, 'Also push to docker registry with "latest" tag.')
    stringParam('SPLENV_REF', null, 'rubin-env version, eups tag, or conda env ref')
    stringParam('RUBINENV_VER', null, 'rubin-env version and container tag')
  }
}
