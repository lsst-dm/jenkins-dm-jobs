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
  // the timeout should be <= the cron triggering interval to prevent builds
  // pilling up in the backlog.
  timeout(time: 23, unit: 'HOURS') {
    util.buildStackOsMatrix(true)
  }
} // notify.wrap
