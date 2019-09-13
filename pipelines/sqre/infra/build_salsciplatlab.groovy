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
  }
}

notify.wrap {
  util.requireParams([
    'BASE_IMAGE',
    'IMAGE_NAME',
    'JLBLEED',
    'NO_PUSH',
    'TAG',
    'TIMEOUT',
  ])

  String tag         = params.TAG
  Boolean pushDocker = (! params.NO_PUSH.toBoolean())
  Boolean jlBleed    = params.JLBLEED
  String pyver       = params.PYVER // use as opaque string
  String baseImage   = params.BASE_IMAGE
  String imageName   = params.IMAGE_NAME
  Integer timelimit  = params.TIMEOUT

  def run = {
    stage('checkout') {
      def branch = 'prod'
      if (jlBleed) {
        branch = 'master'
      }
      git([
        url: 'https://github.com/lsst-sqre/sal-sciplat-lab',
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
        if (jlBleed) {
          opts = '-j'
        }
        if (pushDocker) {
          docker.withRegistry(
            'https://index.docker.io/v1/',
            'dockerhub-sqreadmin'
          ) {
            util.bash """
              ./bld \
               -b '${baseImage}' \
               -n '${imageName}' \
               ${opts} \
               '${tag}'
            """
          }
        } else {
          util.bash """
              ./bld \
               -d \
               -x \
               -b '${baseImage}' \
               -n '${imageName}' \
               ${opts} \
               '${tag}'
              docker build .
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
