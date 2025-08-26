import util.Plumber
import org.yaml.snakeyaml.Yaml

def scipipe = new Yaml().load(readFileFromWorkspace('etc/scipipe/build_matrix.yaml'))

def p = new Plumber(name: 'stack-os-matrix', dsl: this)
p.pipeline().with {
  description('Execute a build of EUPS products using `lsstsw`.')

  parameters {
    stringParam('REFS', null, 'Whitespace delimited list of "refs" to attempt to build.  Priority is highest -> lowest from left to right.  "main" or branch from repos.yaml is implicitly appended to the right side of the list, if not specified; do not specify "main" explicitly.')
    stringParam('PRODUCTS', scipipe.canonical.products + " lsst_sitcom",
      'Whitespace delimited list of EUPS products to build.')
    stringParam('SPLENV_REF', scipipe.template.splenv_ref, 'conda env ref')
    // XXX testing only
    //booleanParam('NO_FETCH', false, 'Do not pull from git remote if branch is already the current ref. (This should generally be false outside of testing the CI system)')
    booleanParam('NO_BINARY_FETCH', false, 'if enable, will build all binaries from scratch')
    booleanParam('LOAD_CACHE', true, 'if enable, will load cache from gcp')
  }

  environmentVariables(
    BUILD_CONFIG: 'scipipe-lsstsw-matrix',
    BUILD_DOCS: false,
    WIPEOUT: false,
    SAVE_CACHE: false,
  )
}
