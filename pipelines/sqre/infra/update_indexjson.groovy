node('jenkins-manager') {
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
    // def image = docker.image("${hub_repo}:latest")
    def cwd      = pwd()
    def ciDir    = "${cwd}/ci-scripts"
    dir('ci-scripts') {
      util.cloneCiScripts()
    }

    stage('update index file') {
      // image.pull()
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
            docker.image("${hub_repo}:alpine").inside {
                util.posixSh '''
                gcloud auth activate-service-account $SERVICEACCOUNT --key-file=$GOOGLE_APPLICATION_CREDENTIALS;
                python3 ci-scripts/updateindexfile.py
                '''
            }
          }
        } // withCredentials
      }
    }

  } // run

  util.nodeWrap(architecture) {
    timeout(time: 1, unit: 'HOURS') {
      run()
    }
  } // util.nodeWrap('linux-64')
} // notify.wrap
