import util.Plumber

def p = new Plumber(
  name: 'sqre/infra/aggregate-sal',
  dsl: this
)
p.pipeline().with {
    description('Constructs docker SAL-enhanced LSST Science Platform Notebook Aspect images, ensuring that aggregated builds (where the SAL object versions are identical, represented by symlinks) happen on the same host, to avoid pointless rebuilds.')

  parameters {
    stringParam('TAG', null, 'sciplat-lab tag')
    stringParam('TIMEOUT', '4', 'build timeout in hours')
    stringParam('ENVIRONMENTS', '', 'space-separated environment list, e.g "nts tts base"')
  }

  concurrentBuild(true)
}
