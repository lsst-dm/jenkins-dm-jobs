import util.Plumber

def p = new Plumber(
  name: 'sqre/infra/build-sciplatlab',
  dsl: this
)
p.pipeline().with {
  description('Constructs Rubin Science Platform Notebook Aspect docker images.')

  parameters {
    stringParam('TAG', null, 'eups distrib tag')
    stringParam('SUPPLEMENTARY', null, 'Make experimental image exp_\<tag\>_\<supplementary\>')
    booleanParam('NO_PUSH', false, 'Do not push image to docker registry')
    stringParam('BRANCH', 'prod', 'Branch to build ("prod")')
    stringParam('TIMEOUT', '4', 'build timeout in hours')
  }

  concurrentBuild(true)
}
