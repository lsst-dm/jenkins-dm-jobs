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
  def requiredParams = [
    'PRODUCT',
    'TAG',
    'TIMEOUT',
  ]

  requiredParams.each { p ->
    if (!params.get(p)) {
      error "${p} parameter is required"
    }
  }

  def product = params.PRODUCT
  def tag = params.TAG
  def timelimit = params.TIMEOUT.toInteger()

  def baseImage = 'lsstsqre/centos:7-stackbase'
  def hubRepo = 'lsstsqre/centos'
  def slug = "${hubRepo}:7-stack-lsst_distrib-${params.TAG}"

  def run = {
    stage('checkout') {
      git([
        url: 'https://github.com/lsst-sqre/docker-tarballs',
        branch: 'master'
      ])
    }

    stage('pull') {
      def image = docker.image(baseImage)
      image.pull()
    }

    stage('build') {
      util.shColor """
        docker build \
          --build-arg PRODUCT=\"${product}\" \
          --build-arg TAG=\"${tag}\" \
          -t \"${slug}\" .
        """
    }

    stage('push') {
      docker.withRegistry(
        'https://index.docker.io/v1/',
        'dockerhub-sqreadmin'
      ) {
        util.shColor "docker push \"${slug}\""
      }
    }
  } // run

  node('docker') {
    timeout(time: timelimit, unit: 'HOURS') {
      run()
    }
  }
} // notify.wrap
