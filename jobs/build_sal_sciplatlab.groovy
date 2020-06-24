import util.Plumber

def p = new Plumber(
  name: 'sqre/infra/build-sal-sciplatlab',
  dsl: this
)
p.pipeline().with {
  description('Constructs docker SAL-enhanced LSST Science Platform Notebook Aspect images.')

  parameters {
    stringParam('TAG', null, 'sciplat-lab tag')
    stringParam('TIMEOUT', '4', 'build timeout in hours')
    stringParam('ENVIRONMENT', '', 'Build for specific environment, e.g "summit"')
  }

  concurrentBuild(true)
}
