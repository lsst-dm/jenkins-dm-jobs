import util.Plumber

def p = new Plumber(name: 'sqre/infrastructure/build-gitlfs', dsl: this)
p.pipeline().with {
  description('Constructs docker git-lfs images.')

  parameters {
    stringParam('LFS_VER', '2.3.4', 'Git lfs version to install')
    booleanParam('NO_PUSH', false, 'Do not push image to docker registry.')
    booleanParam('LATEST', false, 'Also push to docker registry with "latest" tag.')
  }
}
