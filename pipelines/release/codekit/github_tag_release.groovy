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
  util.requireParams(['DRY_RUN', 'GIT_TAG', 'EUPS_TAG', 'BUILD_ID'])

  options = [
    '--dry-run': params.DRY_RUN,
  ]

  if (params.LIMIT) {
    options.'--limit' =  params.LIMIT
  }

  node('docker') {
    util.githubTagRelease(
      params.GIT_TAG,
      params.EUPS_TAG,
      params.BUILD_ID,
      options
    )
  } // node
} // notify.wrap
