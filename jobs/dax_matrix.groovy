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
    stringParam('PRODUCT', 'qserv_distrib', 'Whitespace delimited list of EUPS products to build.')
  }

  concurrentBuild(true)

  environmentVariables(
    BUILD_CONFIG: 'dax-lsstsw-matrix',
    SKIP_DOCS: true,
    WIPEOUT: false,
  )
}
