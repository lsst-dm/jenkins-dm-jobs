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

  String architecture = params.ARCHITECTURE
  Boolean noPush         = params.NO_PUSH

  def hub_repo = 'gcr.io/google.com/cloudsdktool/google-cloud-cli'

  def run = {
    // def image = docker.image("${hub_repo}:latest")
    def cwd      = pwd()
    def ciDir    = "${cwd}/ci-scripts"
    def homeDir = "${cwd}/home"
    dir('ci-scripts') {
      util.cloneCiScripts()
    }
    dir('lsstsw') {
      cloneLsstsw()
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
          ]) {

             bash '''
               mkdir -p lsstsw/miniconda/conda-meta
               touch lsstsw/miniconda/conda-meta/history
               gcloud auth activate-service-account $SERVICEACCOUNT --key-file=$GOOGLE_APPLICATION_CREDENTIALS;
             '''
             def buildEnv = [
               "WORKSPACE=${cwd}",
               "HOME=${homeDir}",
               "EUPS_USERDATA=${homeDir}/.eups_userdata",
               "NODE_LABELS=${nodeLabels}"
             ]

             // Map -> List
             buildParams.each { pair ->
               buildEnv += pair.toString()
             }
             withEnv(buildEnv) {
               bash './ci-scripts/backuplsststack.sh'
             }
        }
      }
    } // if
  } // run
  util.nodeWrap(architecture) {
    timeout(time: 1, unit: 'HOURS') {
      run()
    }
  } // util.nodeWrap('linux-64')
} // notify.wrap
