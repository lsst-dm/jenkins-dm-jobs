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
  util.requireParams([
    'ARCHITECTURE',
    'NO_PUSH',
    ])

  String architecture    = params.ARCHITECTURE
  String splenv_ref      = params.SPLENV_REF
  String mini_ver        = params.MINI_VER
  Boolean noPush         = params.NO_PUSH


  def hub_repo = 'gcr.io/google.com/cloudsdktool/google-cloud-cli'

  def run = {
    // Clone ci-scripts and run the updater in ONE pod sharing the emptyDir
    // workspace; the gcloud image provides both gcloud and python3.
    util.insideK8sContainer(
      image: "${hub_repo}:alpine",
      pull: true,
      arch: (architecture == 'linux-aarch64') ? 'arm64' : 'amd64',
      // Only holds a ci-scripts clone, so make the emptyDir above actually true:
      // renderPodYaml otherwise defaults /j to a 300Gi hyperdisk and requests
      // 8 CPU/32Gi. The release pipelines call this twice with wait:true, so that
      // overhead landed on the critical path both times.
      emptyDirWorkspace: true,
      storage: '2Gi',
      cpuRequest: '1',
      cpuLimit: '2',
      memRequest: '2Gi',
      memLimit: '4Gi',
    ) {
      dir('ci-scripts') {
        util.cloneCiScripts()
      }

      stage('update index file') {
        if (!noPush) {
          withCredentials([file(
            credentialsId: 'gs-eups-push',
            variable: 'GOOGLE_APPLICATION_CREDENTIALS'
          )]) {
            withEnv([
              "SERVICEACCOUNT=eups-dev@prompt-proto.iam.gserviceaccount.com",
              "SPLENV_REF=${splenv_ref}",
              "MINI_VER=${mini_ver}",
            ]) {
              util.posixSh '''
              gcloud auth activate-service-account $SERVICEACCOUNT --key-file=$GOOGLE_APPLICATION_CREDENTIALS;
              python3 ci-scripts/updateindexfile.py
              '''
            }
          } // withCredentials
        }
      }
    } // util.insideK8sContainer
  } // run

  // No outer nodeWrap: the pod is the agent.
  timeout(time: 1, unit: 'HOURS') {
    run()
  }
} // notify.wrap
