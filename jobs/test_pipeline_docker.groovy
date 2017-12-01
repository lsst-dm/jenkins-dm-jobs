import util.Plumber

def p = new Plumber(name: 'sqre/ci-ci/test-pipeline-docker', dsl: this)
p.pipeline().with {
  description('Test running a build inside a docker container.')
}
