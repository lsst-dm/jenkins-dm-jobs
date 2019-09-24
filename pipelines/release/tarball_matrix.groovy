node {
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
    scipipe = util.scipipeConfig()
  }
}

notify.wrap {
  util.requireParams([
    'EUPS_TAG',
    'PRODUCTS',
    'PUBLISH',
    'RUN_SCONS_CHECK',
    'SMOKE',
  ])

  String eupsTag        = params.EUPS_TAG
  String products       = params.PRODUCTS
  Boolean publish       = params.PUBLISH
  Boolean runSconsCheck = params.RUN_SCONS_CHECK
  Boolean smoke         = params.SMOKE

  def tarballProducts = scipipe.tarball.products
  def retries         = 3

  timeout(time: 30, unit: 'HOURS') {
    stage('build eups tarballs') {
      util.buildTarballMatrix(
        tarballConfigs: scipipe.tarball.build_config,
        parameters: [
          PRODUCTS: tarballProducts,
          EUPS_TAG: eupsTag,
          SMOKE: smoke,
          RUN_SCONS_CHECK: runSconsCheck,
          PUBLISH: publish,
        ],
        retries: retries,
      )
    } // stage
  }
} // notify.wrap
