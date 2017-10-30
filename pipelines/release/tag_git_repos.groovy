def notify = null

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
  def timelimit = params.TIMEOUT.toInteger()

  withCredentials([[
    $class: 'StringBinding',
    credentialsId: 'github-api-token-sqreadmin',
    variable: 'GITHUB_TOKEN'
  ]]) {
    util.shColor '''
      #!/bin/bash -e

      ARGS=()
      if [[ $DRY_RUN == "true" ]]; then
        ARGS+=('--dry-run')
      fi

      # do not echo GH token to console log
      set +x
      ARGS+=('--token' "$GITHUB_TOKEN")
      set -x

      ARGS+=('--org' 'lsst')
      ARGS+=('--team' 'Data Management')
      ARGS+=('--email' 'sqre-admin@lists.lsst.org')
      ARGS+=('--tagger' 'sqreadmin')
      ARGS+=('--fail-fast')
      ARGS+=('--debug')
      ARGS+=("$GIT_TAG")
      ARGS+=("$BUILD_ID")

      virtualenv venv
      . venv/bin/activate
      pip install sqre-codekit==3.1.0

      # do not echo GH token to console log
      set +x
      github-tag-version "${ARGS[@]}"
    '''
  }

  // python 2.7 is required
  node('centos-7') {
    timeout(time: timelimit, unit: 'HOURS') {
      run()
    }
  }
} // notify.wrap
