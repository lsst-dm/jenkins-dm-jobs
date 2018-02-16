import util.Plumber

def p = new Plumber(name: 'sqre/ci-ci/ghslacker', dsl: this)
p.pipeline().with {
  description('Lookup slack user id via ghslacker microservice')

  parameters {
    stringParam('GITHUB_USER', null)
  }

  concurrentBuild(true)
}
