node('jenkins-master') {
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
    config = util.readYamlFile 'etc/science_pipelines/build_matrix.yaml'
  }
}

notify.wrap {
  def retries = 3

  def requiredParams = [
    'PRODUCT',
    'EUPS_TAG',
  ]

  requiredParams.each { p ->
    if (!params.get(p)) {
      error "${p} parameter is required"
    }
  }

  def opt = [
    SMOKE: params.SMOKE,
    RUN_DEMO: params.RUN_DEMO,
    RUN_SCONS_CHECK: params.RUN_SCONS_CHECK,
    PUBLISH: params.PUBLISH,
  ]

  timeout(time: 30, unit: 'HOURS') {
    stage('build eups tarballs') {
      util.buildTarballMatrix(config, params.PRODUCT, params.EUPS_TAG, opt)
    }
  }
} // notify.wrap
