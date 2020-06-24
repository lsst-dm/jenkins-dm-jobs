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
    scipipe = util.scipipeConfig() // needed for side effects
    sqre = util.sqreConfig() // needed for side effects
  }
}

notify.wrap {
  util.requireParams([
    'TAG',
    'TIMEOUT',
    'ENVIRONMENT'
  ])

  String tag         = params.TAG
  Integer timelimit  = params.TIMEOUT
  String environment = params.ENVIRONMENT

  def run = {
    stage('checkout') {
      def branch = 'master'
      def baseImage = 'sciplat-lab'
      git([
        url: 'https://github.com/lsst-sqre/sal-sciplat-lab',
        branch: branch
      ])
    }

    // ensure the current image is used
    stage('docker pull') {
      docImage = "${baseImage}:${tag}"
      docker.image(docImage).pull()
    }

    stage('build+push') {
      def opts = ''
      if (environment) {
          opts = "-e ${environment}"
      }
      docker.withRegistry(
        'https://index.docker.io/v1/',
        'dockerhub-sqreadmin'
        ) {
            util.bash """
              ./bld \
               ${opts} \
               '${tag}'
            """
          }
    }      
  } // run

  util.nodeWrap('docker') {
    timeout(time: timelimit, unit: 'HOURS') {
      run()
    }
  } // util.nodeWrap
} // notify.wrap
