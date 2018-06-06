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
  util.requireParams(['DRY_RUN', 'GIT_TAG'])

  node('docker') {
    util.githubTagTeams(
      [
        '--dry-run': params.DRY_RUN,
        '--tag': params.GIT_TAG,
      ]
    )
  } // node
} // notify.wrap
