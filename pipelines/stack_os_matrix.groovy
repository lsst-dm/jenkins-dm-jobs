node('jenkins-manager') {
  dir('jenkins-dm-jobs') {
    checkout([
      $class: 'GitSCM',
      branches: scm.getBranches(),
      userRemoteConfigs: scm.getUserRemoteConfigs(),
      changelog: false,
      poll: false
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
    'LOAD_CACHE'
  ])

  if (params.SAVE_CACHE) {
    SAVE_CACHE = true
  }

  def buildParams = [
    LSST_REFS:              REFS,
    LSST_PRODUCTS:          PRODUCTS,
    LSST_BUILD_DOCS:        BUILD_DOCS,
    LSST_NO_BINARY_FETCH:   NO_BINARY_FETCH,
  ]

  // override conda env ref from build_matrix.yaml
  if (params.SPLENV_REF) {
    buildParams['LSST_SPLENV_REF'] = params.SPLENV_REF
  }

  def lsstswConfigs = scipipe[BUILD_CONFIG]
  if (lsstswConfigs == null) {
    error "invalid value for BUILD_CONFIG: ${BUILD_CONFIG}"
  }

  timeout(time: 12, unit: 'HOURS') {
    stage('build') {
      util.lsstswBuildMatrix(lsstswConfigs, buildParams, WIPEOUT.toBoolean(), LOAD_CACHE.toBoolean(), SAVE_CACHE.toBoolean() )
    }
  }
} // notify.wrap
