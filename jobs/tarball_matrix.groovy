import util.Plumber

def p = new Plumber(name: 'release/tarball-matrix', dsl: this)
p.pipeline().with {
  description('build and publish EUPS distrib "tarball" packages')

  parameters {
    stringParam('PRODUCT', 'lsst_distrib', 'Whitespace delimited list of EUPS products to build.')
    stringParam('EUPS_TAG', null, 'published EUPS tag')
    booleanParam('SMOKE', true, 'Run a post-build installation test of generated EUPS distrib traballs.')
    booleanParam('RUN_DEMO', true, '(no-op without SMOKE) Run the "stack" demo as part of the "smoke" installation test.')
    booleanParam('RUN_SCONS_CHECK', true, '(no-op without SMOKE) Manually checkout the "base" product and invoke "scons".')
    booleanParam('PUBLISH', false, 'Publish generated EUPS distrib tarballs.')
  }

  concurrentBuild(true)
}
