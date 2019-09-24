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
    sqre = util.sqreConfig() // side effect only
  }
}

notify.wrap {
  util.requireParams([
    'DRY_RUN',
    'EUPS_TAG',
    'GIT_TAG',
    'LIMIT',
    'MANIFEST_ID',
    'MANIFEST_ONLY',
    'VERIFY',
  ])

  // note that EUPS_TAG and MANIFEST_ONLY are mutually exclusive -- because of
  // this, EUPS_TAG is "optional"
  Boolean dryRun      = params.DRY_RUN
  String eupsTag      = util.emptyToNull(params.EUPS_TAG) // '' means null
  String gitTag       = params.GIT_TAG
  String limit        = params.LIMIT // using as string; do not convert to int
  String manifestId   = params.MANIFEST_ID
  String manifestOnly = params.MANIFEST_ONLY
  Boolean verify      = params.VERIFY

  options = [
    '--org': scipipe.release_tag_org,
    '--dry-run': dryRun,
    '--manifest': manifestId,
  ]

  if (eupsTag) {
    options.'--eups-tag' = eupsTag
  }

  if (limit) {
    options.'--limit' = limit
  }

  if (verify) {
    options.'--verify' = true
  }

  if (manifestOnly) {
    options.'--manifest-only' = true
  }

  util.nodeWrap('docker') {
    util.githubTagRelease(
      options: options,
      args: [gitTag],
    )
  } // util.nodeWrap
} // notify.wrap
