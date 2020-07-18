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
    'ENVIRONMENTS'
  ])

  String tag         = params.TAG
  Integer timelimit  = params.TIMEOUT
  String environments = params.ENVIRONMENTS

  def run = {
    stage('checkout') {
      def branch = 'master'
      git([
        url: 'https://github.com/lsst-sqre/sal-sciplat-lab',
        branch: branch
      ])
    }

    // ensure the correct input image is used
    stage('docker pull') {
      def baseImage = 'lsstsqre/sciplat-lab'
      docImage = "${baseImage}:${tag}"
      docker.image(docImage).pull()
    }

    stage('build+push') {
      def opts = "-t ${tag}"
      docker.withRegistry(
        'https://index.docker.io/v1/',
        'dockerhub-sqreadmin'
        ) {
            util.bash """
              ./aggregator \
               ${opts} \
               ${environments}
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
