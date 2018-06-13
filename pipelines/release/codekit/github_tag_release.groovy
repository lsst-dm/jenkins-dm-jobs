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
    config = util.scipipeConfig()
  }
}

notify.wrap {
  util.requireParams([
    'MANIFEST_ID',
    'DRY_RUN',
    'EUPS_TAG',
    'GIT_TAG',
    'LIMIT',
    'VERIFY',
  ])

  String manifestId = params.MANIFEST_ID
  Boolean dryRun    = params.DRY_RUN
  String eupsTag    = params.EUPS_TAG
  String gitTag     = params.GIT_TAG
  String limit      = params.LIMIT // using as string; do not convert to int
  Boolean verify    = params.VERIFY

  options = [
    '--org': config.release_tag_org,
    '--dry-run': dryRun,
    '--manifest': manifestId,
    '--eups-tag': eupsTag,
  ]

  if (limit) {
    options.'--limit' = limit
  }

  if (verify) {
    options.'--verify' = true
  }

  node('docker') {
    util.githubTagRelease(
      options: options,
      args: [gitTag],
    )
  } // node
} // notify.wrap
