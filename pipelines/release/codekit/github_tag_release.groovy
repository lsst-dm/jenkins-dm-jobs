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
  util.requireParams([
    'BUILD_ID',
    'DRY_RUN',
    'EUPS_TAG',
    'GIT_TAG',
    'VERIFY',
  ])

  options = [
    '--org': config.release_tag_org,
    '--dry-run': params.DRY_RUN,
  ]

  if (params.LIMIT) {
    options.'--limit' = params.LIMIT
  }

  if (params.VERIFY) {
    options.'--verify' = true
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
