import util.Plumber

def p = new Plumber(name: 'sqre/ci-ci/test-pipeline-config-file', dsl: this)
p.pipeline().with {
  description('Test reading a config file from the jenkins-dm-jobs repo.')
}
