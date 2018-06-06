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
  }
}

notify.wrap {
  util.requireParams(['DRY_RUN', 'GIT_TAG', 'BUILD_ID'])

  node('docker') {
    util.githubTagRelease(
      params.GIT_TAG,
      params.BUILD_ID,
      [
        '--dry-run': params.DRY_RUN,
      ]
    )
  } // node
} // notify.wrap
