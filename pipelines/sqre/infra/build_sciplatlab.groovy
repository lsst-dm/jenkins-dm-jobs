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
    'BASE_IMAGE',
    'IMAGE_NAME',
    'NO_PUSH',
    'FLATTEN',
    'VERBOSE',
    'JLBLEED',
    'TAG',
    'TAG_PREFIX',
    'TIMEOUT',
  ])

  String tag         = params.TAG
  Boolean jlbleed    = params.JLBLEED
  Boolean pushDocker = (! params.NO_PUSH.toBoolean())
  Boolean flatten    = params.FLATTEN.toBoolean()
  Boolean verbose    = params.VERBOSE.toBoolean()
  String baseImage   = params.BASE_IMAGE
  String imageName   = params.IMAGE_NAME
  String tagPrefix   = params.TAG_PREFIX
  Integer timelimit  = params.TIMEOUT

  def run = {
    stage('checkout') {
      def branch = 'prod'
      if (jlbleed) {
        branch = 'master'
      }
      git([
        url: 'https://github.com/lsst-sqre/nublado',
        branch: branch
      ])
    }

    // ensure the current image is used
    stage('docker pull') {
      docImage = "${baseImage}:${tagPrefix}${tag}"
      docker.image(docImage).pull()
    }

    stage('build+push') {
      def opts = ''
      if (jlbleed) {
        opts = '-e -s jlbleed'
      }
      if (flatten) {
        if (opts) {
          opts = "-f ${opts}"
        } else {
          opts = '-f'
        }
      }
      if (verbose) {
        if (opts) {
          opts = "-v ${opts}"
        } else {
          opts = '-v'
        }
      }
      dir('jupyterlab') {
        if (pushDocker) {
          docker.withRegistry(
            'https://index.docker.io/v1/',
            'dockerhub-sqreadmin'
          ) {
            util.bash """
              ./bld \
               -b '${baseImage}' \
               -n '${imageName}' \
               -t '${tagPrefix}' \
               ${opts} \
               '${tag}'
            """
          }
        } else {
          util.bash """
              ./bld \
               -x \
               -b '${baseImage}' \
               -n '${imageName}' \
               -t '${tagPrefix}' \
               ${opts} \
               '${tag}'
              docker build .
          """
        }
      }
    }
  } // run

  util.nodeWrap('docker') {
    timeout(time: timelimit, unit: 'HOURS') {
      run()
    }
  } // util.nodeWrap
} // notify.wrap
