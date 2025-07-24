import util.Plumber

def p = new Plumber(name: 'sqre/infra/update_indexjson', dsl: this)
p.pipeline().with {
  description('Update index.json file for gcp bucket')

  parameters {
    stringParam('ARCHITECTURE', 'linux-64', 'Architecture to run script.')
    booleanParam('NO_PUSH', false, 'Do not push image to docker registry.')
  }
}
