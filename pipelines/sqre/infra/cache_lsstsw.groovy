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
  Boolean noPush      = params.NO_PUSH
  Boolean nobinary    = params.NO_BINARY_FETCH
  String date_tag     = params.DATE_TAG

  def run = {
    def cwd      = pwd()
    def ciDir    = "${cwd}/ci-scripts"
    def homeDir = "${cwd}/home"
    def canonical    = scipipe.canonical
    def lsstswConfig = canonical.lsstsw_config

    def splenvRef = lsstswConfig.splenv_ref
    def buildParams = [
      EUPS_PKGROOT:          "${cwd}/distrib",
      GIT_SSH_COMMAND:       'ssh -o StrictHostKeyChecking=no',
      K8S_DIND_LIMITS_CPU:   "4",
      LSST_COMPILER:         lsstswConfig.compiler,
      LSST_NO_BINARY_FETCH:  true,
      LSST_PYTHON_VERSION:   lsstswConfig.python,
      LSST_SPLENV_REF:       splenvRef,
    ]

    stage('build') {
      util.jenkinsWrapper(buildParams)

    }
    stage('update index file') {
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
               "BUILDDIR=${buildDir}",
               "DATE_TAG=${date_tag}"
             ]

             withEnv(buildEnv) {
               util.posixSh("""
               eval "\$(${BUILDDIR}/conda/miniconda3-py38_4.9.2/bin/conda shell.bash hook)"
               if conda env list | grep gcloud-env > /dev/null 2>&1; then
                 conda activate gcloud-env
                 conda update google-cloud-sdk
               else
                 conda create -y --name gcloud-env google-cloud-sdk
                 conda activate gcloud-env
               fi
               bash './ci-scripts/backuplsststack.sh $DATE_TAG'
               """
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
