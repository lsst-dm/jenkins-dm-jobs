import util.Plumber
import org.yaml.snakeyaml.Yaml

def scipipe = new Yaml().load(readFileFromWorkspace('etc/scipipe/build_matrix_test.yaml'))

def p = new Plumber(name: 'test-stack-os-matrix', dsl: this)
p.pipeline().with {
  description('Execute a build of EUPS products using `lsstsw` (TEST).')

  parameters {
    stringParam('REFS', null, 'Whitespace delimited list of "refs" to attempt to build.  Priority is highest -> lowest from left to right.  "master" or branch from repos.yaml is implicitly appended to the right side of the list, if not specified; do not specify "master" explicitly.')
    stringParam('PRODUCTS', scipipe.canonical.products,
      'Whitespace delimited list of EUPS products to build.')
    stringParam('SPLENV_REF', scipipe.template.splenv_ref, 'conda env ref')
    stringParam('RUBINENV_ORG_FORK', null, 'Organization where to look for rubinenv-feedstock fork.')
    stringParam('RUBINENV_BRANCH', null, 'Branch to test in the rubinenv-feedstock fork.')
  }

  concurrentBuild(true)

  environmentVariables(
    BUILD_CONFIG: 'scipipe-lsstsw-matrix',
    BUILD_DOCS: false,
    WIPEOUT: true,
  )
}
