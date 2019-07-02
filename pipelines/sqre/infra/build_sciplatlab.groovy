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
    'NO_PUSH',
    'PYVER',
    'TAG',
    'TAG_PREFIX',
    'TIMEOUT',
  ])

  String tag         = params.TAG
  Boolean jlbleed    = params.JLBLEED
  Boolean pushDocker = (! params.NO_PUSH.toBoolean())
  String pyver       = params.PYVER // use as opaque string
  String baseImage   = params.BASE_IMAGE
  String imageName   = params.IMAGE_NAME
  String tagPrefix   = params.TAG_PREFIX
  Integer timelimit  = params.TIMEOUT

  def run = {
    stage('checkout') {
      branch = 'prod'
      if (jlbleed) {
        branch = 'master'
      }
      git([
        url: 'https://github.com/lsst-sqre/jupyterlabdemo',
        branch: branch
      ])
    }

    // ensure the current image is used
    stage('docker pull') {
      docImage = "${baseImage}:${tagPrefix}${tag}"
      docker.image(docImage).pull()
    }

    stage('build+push') {
      def opts = ""
      if (jlbleed) {
        opts = '-e -s jlbleed'
      }
      dir('jupyterlab') {
        if (pushDocker) {
          docker.withRegistry(
            'https://index.docker.io/v1/',
            'dockerhub-sqreadmin'
          ) {
            util.bash """
              ./bld \
               -p '${pyver}' \
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
               -d \
               -p '${pyver}' \
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

  node('docker') {
    timeout(time: timelimit, unit: 'HOURS') {
      run()
    }
  } // node
} // notify.wrap
