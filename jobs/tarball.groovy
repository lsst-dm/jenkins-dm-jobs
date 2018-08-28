import util.Plumber

def p = new Plumber(name: 'release/tarball', dsl: this)
p.pipeline().with {
  description('build and publish EUPS distrib "tarball" packages')

  parameters {
    stringParam('PRODUCT', 'lsst_distrib', 'Whitespace delimited list of EUPS products to build.')
    stringParam('EUPS_TAG', null, 'published EUPS tag')
    booleanParam('SMOKE', false, 'Run a post-build installation test of generated EUPS distrib traballs.')
    booleanParam('RUN_DEMO', false, '(no-op without SMOKE) Run the "stack" demo as part of the "smoke" installation test.')
    booleanParam('RUN_SCONS_CHECK', false, '(no-op without SMOKE) Manually checkout the "base" product and invoke "scons".')
    booleanParam('PUBLISH', false, 'Publish generated EUPS distrib tarballs.')
    booleanParam('WIPEOUT', false, 'Completely wipe out workspace(s) before starting build.')
    stringParam('TIMEOUT', '8', 'build timeout in hours')
    stringParam('IMAGE', null, 'published EUPS tag')
    choiceParam('LABEL', ['centos-7', 'centos-6', 'osx-10.11'], 'Jenkins build agent label')
    stringParam('COMPILER', null, 'compiler version string')
    choiceParam('PYTHON_VERSION', ['3', '2'], 'Python major version')
    choiceParam('MINIVER', ['4.5.4', '4.3.21', '4.2.12'], 'Miniconda installer version')
    choiceParam('LSSTSW_REF', ['fcd27eb', '10a4fa6', '7c8e67'], 'LSST conda package set ref')
    choiceParam('OSFAMILY', ['redhat', 'osx'], 'Published osfamily name')
    stringParam('PLATFORM', null, 'Published platform name')
  }

  concurrentBuild(true)
}
