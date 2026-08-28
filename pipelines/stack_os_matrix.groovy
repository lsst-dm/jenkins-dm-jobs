node('jenkins-manager') {
  dir('jenkins-dm-jobs') {
    checkout([
      $class: 'GitSCM',
      branches: scm.getBranches(),
      userRemoteConfigs: scm.getUserRemoteConfigs(),
    ])
    notify = load 'pipelines/lib/notify.groovy'
    util = load 'pipelines/lib/util.groovy'
    scipipe = util.scipipeConfig() // needed for side effects
    sqre = util.sqreConfig() // needed for side effects
  }
}

notify.wrap {
  // env vars are used instead of the params object so that "params" can be
  // set statically as env vars in calling jobs without exposing a "job param"
  // in the jenkins ui.
  util.requireEnvVars([
    'REFS',
    'BUILD_CONFIG',
    'PRODUCTS',
    'BUILD_DOCS',
    'WIPEOUT',
    'NO_BINARY_FETCH',
    'LOAD_CACHE',
    'SAVE_CACHE'
  ])

  PRODUCTS = util.validateProducts(PRODUCTS)

  def buildParams = [
    LSST_REFS:              REFS,
    LSST_PRODUCTS:          PRODUCTS,
    LSST_BUILD_DOCS:        BUILD_DOCS,
    LSST_NO_BINARY_FETCH:   NO_BINARY_FETCH,
  ]

  if (PRODUCTS.contains("ci_lsstcam")){
      buildParams["CI_LSSTCAM"] = true
  }

  // override conda env ref from build_matrix.yaml
  if (params.SPLENV_REF) {
    buildParams['LSST_SPLENV_REF'] = params.SPLENV_REF
  }

  // SAVE_CACHE/LOAD_CACHE are read as env vars rather than params because this
  // script backs two kinds of job: stack-os-matrix, which declares them as
  // parameters, and the CleanBuild jobs, which only set them as env vars.
  // Declared parameters are exposed as env vars too, so the env var is the one
  // spelling that works for both.

  // Release pipelines save under a per-build temporary tag and promote it to
  // d_latest only once the release has succeeded, so a failed release cannot
  // leave the shared cache pointing at a stack that was never published.
  def saveCacheTag = params.SAVE_CACHE_TAG ?: 'd_latest'

  def lsstswConfigs = scipipe[BUILD_CONFIG]
  if (lsstswConfigs == null) {
    error "invalid value for BUILD_CONFIG: ${BUILD_CONFIG}"
  }

  timeout(time: 12, unit: 'HOURS') {
    stage('build') {
      util.lsstswBuildMatrix(lsstswConfigs, buildParams, WIPEOUT.toBoolean(), LOAD_CACHE.toBoolean(), SAVE_CACHE.toBoolean(), saveCacheTag)
    }
  }
} // notify.wrap
