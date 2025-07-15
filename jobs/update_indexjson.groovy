import util.Plumber

def p = new Plumber(name: 'sqre/update_indexjson', dsl: this)
p.pipeline().with {
  description('Update index.json file for gcp bucket')

  parameters {
    stringParam('ARCHITECTURE', 'linux-64', 'Architecture to run metric.')
    booleanParam('NO_PUSH', true, 'Do not push to gcp')
  }
}
