node('jenkins-manager') {
  dir('jenkins-dm-jobs') {
    checkout([
      $class: 'GitSCM',
      branches: scm.getBranches(),
      userRemoteConfigs: scm.getUserRemoteConfigs(),
    ])
    notify = load 'pipelines/lib/notify.groovy'
    util = load 'pipelines/lib/util.groovy'
    scipipe = util.scipipeConfig() // needed for side effects
    sqre = util.sqreConfig() // needed for side effects
  }
}

notify.wrap {
  def hub_repo = 'ghcr.io/lsst-dm/tag-monger'

  def run = {
    // tag-monger is self-contained: it reads/retires eups tags in a GCS bucket
    // and needs no workspace data, so it runs in a single pod with no sidecar.
    util.insideK8sContainer(
      image: "${hub_repo}:latest",
      pull: true,
    ) {
      stage('retire daily tags') {
        withCredentials([file(
            credentialsId: 'gs-eups-push',
            variable: 'GOOGLE_APPLICATION_CREDENTIALS'
          )])  {
          withEnv([
            "TAG_MONGER_BUCKET=eups-prod",
            "TAG_MONGER_MAX=0",
            "TAG_MONGER_VERBOSE=true",
          ]) {
            util.bash 'tag-monger'
          }
        } // withCredentials
      } // stage
    } // util.insideK8sContainer
  } // run

  // No outer nodeWrap: the pod is the agent.
  timeout(time: 4, unit: 'HOURS') {
    run()
  }
} // notify.wrap
