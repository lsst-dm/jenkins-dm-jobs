import util.Plumber

def p = new Plumber(
  name: 'dax/dax-matrix',
  script: 'pipelines/stack_os_matrix.groovy',
  dsl: this
)
p.pipeline().with {
  description('Execute a build of EUPS products using `lsstsw`.')

  parameters {
    stringParam('BRANCH', null, 'Whitespace delimited list of "refs" to attempt to build.  Priority is highest -> lowest from left to right.  "master" is implicitly appended to the right side of the list, if not specified.')
    stringParam('PRODUCT', 'lsst_distrib', 'Whitespace delimited list of EUPS products to build.')
    booleanParam('SKIP_DEMO', false, 'Do not run the demo after all packages have completed building.')
  }

  concurrentBuild(true)

  environmentVariables(
    WIPEOUT: false,
    BUILD_CONFIG: 'dax-lsstsw-matrix',
  )
}
