import util.Plumber

def p = new Plumber(name: 'sqre/ci-ci/test-pipeline-write', dsl: this)
p.pipeline().with {
  description('Test writing and archiving a file from a pipeline script.')
}
