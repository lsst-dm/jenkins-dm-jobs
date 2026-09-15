import util.Plumber
import org.yaml.snakeyaml.Yaml

def scipipe = new Yaml().load(readFileFromWorkspace('etc/scipipe/build_matrix.yaml'))

def p = new Plumber(name: 'stack-os-matrix', dsl: this)
p.pipeline().with {
  description('Execute a build of EUPS products using `lsstsw`.')

  logRotator {
    daysToKeep(60)
    artifactDaysToKeep(60)
  }

  parameters {
    stringParam('REFS', null, 'Whitespace delimited list of "refs" to attempt to build.  Priority is highest -> lowest from left to right.  "main" or branch from repos.yaml is implicitly appended to the right side of the list, if not specified; do not specify "main" explicitly.')
    stringParam('PRODUCTS', scipipe.canonical.products + " lsst_sitcom",
      'Whitespace delimited list of EUPS products to build.')
    stringParam('SPLENV_REF', scipipe.template.splenv_ref, 'conda env ref')
    choiceParam('PYTHON_PIN', ['', '3.13', '3.14'], 'EXPERIMENTAL.  Build the conda env with a specific python version, eg. "3.14".  Empty (the default) uses the python rubin-env selects, which is the only supported one and the only one the published eups binaries are built for.  A pinned build gets its own conda env and eups stack and does not use the lsstsw cache.')
    // XXX testing only
    //booleanParam('NO_FETCH', false, 'Do not pull from git remote if branch is already the current ref. (This should generally be false outside of testing the CI system)')
    booleanParam('NO_BINARY_FETCH', false, 'if enable, will build all binaries from scratch')
    booleanParam('LOAD_CACHE', true, 'if enable, will load cache from gcp')
    // booleanParam('SAVE_CACHE', false, 'if enable, will upload the built lsstsw tree to gcp as the lsstsw cache')
    // stringParam('SAVE_CACHE_TAG', '', 'Tag to upload the lsstsw cache under when SAVE_CACHE is enabled. Empty (default) means d_latest. Release pipelines pass a per-build temporary tag (eg. the dated eups tag) and promote it to d_latest only once the rest of the release succeeds, so never pass d_latest directly.')
  }

  environmentVariables(
    BUILD_CONFIG: 'scipipe-lsstsw-matrix',
    BUILD_DOCS: false,
    WIPEOUT: false,
  )
}
