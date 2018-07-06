import util.Plumber

def p = new Plumber(name: 'sqre/infra/build-s3cmd', dsl: this)
p.pipeline().with {
  description('Constructs docker s3cmd images.')

  parameters {
    stringParam('S3CMD_VER', '2.0.1', 's3cmd version to install')
    booleanParam('NO_PUSH', false, 'Do not push image to docker registry.')
    booleanParam('LATEST', false, 'Also push to docker registry with "latest" tag.')
  }
}
