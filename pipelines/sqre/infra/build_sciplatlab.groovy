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
    'TAG',
    'SUPPLEMENTARY',
    'NO_PUSH',
    'TIMEOUT',
  ])

  String tag         = params.TAG
  String supplementary = params.SUPPLEMENTARY
  Integer timelimit  = params.TIMEOUT
  Boolean pushDocker = (! params.NO_PUSH.toBoolean())

  def make(m_target, m_args) {
    util.bash """
    make '${m_target}' '${m_args}'
    """
  } // make

  def run = {
    stage('checkout') {
      def branch = 'prod'
      git([
        url: 'https://github.com/lsst-sqre/sciplat-lab',
        branch: branch
      ])
    }

    // ensure the current image is used
    stage('docker pull') {
      docImage = "lsstsqre/centos:7-stack-lsst_distrib-${tag}"
      docker.image(docImage).pull()
    }

    stage('execute') {
      def m_args = 'tag=${tag}'

      if (supplementary) {
        m_args = '${m_args} supplementary=${supplementary}'
      }
      if (pushDocker) {
        docker.withRegistry(
          'https://index.docker.io/v1/',
          'dockerhub-sqreadmin'
        ) { // build and push
            make('push', m_args)
          }
      } else { // just build
        make('image', m_args)
      } // pushDocker clause
    } // execute
  } // run

  util.nodeWrap('docker') {
    timeout(time: timelimit, unit: 'HOURS') {
      run()
    }
  } // util.nodeWrap
} // notify.wrap
