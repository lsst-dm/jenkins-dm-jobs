import util.Plumber

def p = new Plumber(
  name: 'dax/dax-matrix',
  script: 'pipelines/stack_os_matrix.groovy',
  dsl: this
)
p.pipeline().with {
  description('Execute a build of EUPS products using `lsstsw`.')

  parameters {
    stringParam('REFS', null, 'Whitespace delimited list of "refs" to attempt to build.  Priority is highest -> lowest from left to right.  "master" is implicitly appended to the right side of the list, if not specified.')
    stringParam('PRODUCTS', 'qserv_distrib', 'Whitespace delimited list of EUPS products to build.')
    stringParam('SPLENV_REF', '9aa7f31', 'conda env ref (from scipipe_conda_env repo)')
  }

  concurrentBuild(true)

  environmentVariables(
    BUILD_CONFIG: 'dax-lsstsw-matrix',
    BUILD_DOCS: false,
    WIPEOUT: false,
  )
}
