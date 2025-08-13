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
  String date_tag     = params.DATE_TAG
  String splenvRef    = params.SPLENV_REF

  def run = {
    def cwd      = pwd()
    def ciDir    = "${cwd}/ci-scripts"
    def homeDir = "${cwd}/home"
    def lsstswDir = "${cwd}/lsstsw"
    def canonical    = scipipe.canonical
    def lsstswConfig = canonical.lsstsw_config
    def products        = scipipe.canonical.products

    def buildParams = [
      EUPS_PKGROOT:          "${cwd}/distrib",
      GIT_SSH_COMMAND:       'ssh -o StrictHostKeyChecking=no',
      K8S_DIND_LIMITS_CPU:   "8",
      LSST_COMPILER:         lsstswConfig.compiler,
      LSST_NO_BINARY_FETCH:  true,
      LSST_PYTHON_VERSION:   lsstswConfig.python,
      LSST_SPLENV_REF:       splenvRef,
      LSST_PRODUCTS:         products,
      LSST_REFS:             "",
      SCONSFLAGS:            "--no-tests",
    ]

    stage('Prep dir'){
      dir(ciDir) {
        util.cloneCiScripts()
      }
      dir(lsstswDir){
        util.cloneLsstsw()
      }
    }

    stage('build') {
        util.insideDockerWrap(
          image: lsstswConfig.image,
          pull: true,
          args: "-v ${lsstswDir}:${lsstswDir} -v ${ciDir}:${ciDir}",
        ) {
      util.jenkinsWrapper(buildParams)
      }
    }

    stage('update lsstsw cache tarball') {
      if (!noPush) {
        withCredentials([file(
          credentialsId: 'gs-eups-push',
          variable: 'GOOGLE_APPLICATION_CREDENTIALS'
        )]) {
          withEnv([
            "SERVICEACCOUNT=eups-dev@prompt-proto.iam.gserviceaccount.com",
          ]) {
             util.insideDockerWrap(
               image: lsstswConfig.image,
               pull: true,
               args: "-v ${lsstswDir}:${lsstswDir} -v ${ciDir}:${ciDir}",
             ) {
             util.bash '''
               source lsstsw/bin/envconfig
               conda install google-cloud-sdk
             '''

             def buildEnv = [
               "DATE_TAG=${date_tag}"
             ]

             withEnv(buildEnv) {
               util.posixSh("""
                 source lsstsw/bin/envconfig
                 gcloud auth activate-service-account $SERVICEACCOUNT --key-file=$GOOGLE_APPLICATION_CREDENTIALS;
                 cd ci-scripts
                 ./backuplsststack.sh $DATE_TAG
               """)
              }
            }
          }
        }
      } // if
    } // stage
  } // run
  util.nodeWrap(architecture) {
    timeout(time: 6, unit: 'HOURS') {
      run()
    }
  } // util.nodeWrap('architecture')
} // notify.wrap
