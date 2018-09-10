def scipipe = null

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
    scipipe = util.scipipeConfig()
    sqre = util.sqreConfig() // side effect only
  }
}

notify.wrap {
  util.requireParams(['DRY_RUN', 'GIT_TAG'])

  Boolean dryRun = params.DRY_RUN
  String gitTag  = params.GIT_TAG

  node('docker') {
    util.githubTagTeams(
      options: [
        '--dry-run': dryRun,
        '--org': scipipe.release_tag_org,
        '--tag': gitTag,
      ],
    )
  } // node
} // notify.wrap
