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
  def ci_lsstcam = 'stack-os-matrix'
    scipipe = util.scipipeConfig() // needed for side effects

  stage('run ci-lsstcam') {
    build job: ci_lsstcam,
      parameters: [
        stringParam(name: 'REFS', value: null),
        stringParam(name: 'BUILD_CONFIG', value: 'scipipe-lsstsw-matrix'),
        stringParam(name: 'PRODUCTS', value: scipipe.canonical.products + " ci_lsstcam"),
        booleanParam(name: 'BUILD_DOCS', value: false),
        booleanParam(name: 'WIPEOUT', value: false),
        booleanParam(name: 'NO_BINARY_FETCH', value: false),
        booleanParam(name: 'LOAD_CACHE', value: true),
        booleanParam(name: 'SAVE_CACHE', value: false),
      ]
  }
} // notify.wrap
