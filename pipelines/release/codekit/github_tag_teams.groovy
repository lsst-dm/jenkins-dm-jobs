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
  util.requireParams(['DRY_RUN', 'GIT_TAG'])

  node('docker') {
    util.githubTagTeams([
      '--dry-run': params.DRY_RUN,
      '--org': config.release_tag_org,
      '--tag': params.GIT_TAG,
    ])
  } // node
} // notify.wrap
