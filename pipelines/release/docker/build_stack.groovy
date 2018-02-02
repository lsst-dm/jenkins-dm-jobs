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
    'PRODUCT',
    'TAG',
    'TIMEOUT',
  ])

  def product   = params.PRODUCT
  def eupsTag   = params.TAG
  def timelimit = params.TIMEOUT.toInteger()

  def image   = null
  def hubRepo = 'lsstsqre/centos'
  def hubTag  = "7-stack-lsst_distrib-${eupsTag}"

  def run = {
    stage('checkout') {
      git([
        url: 'https://github.com/lsst-sqre/docker-tarballs',
        branch: 'master'
      ])
    }

    stage('build') {
      def opt = []
      // ensure base image is always up to date
      opt << '--pull=true'
      opt << '--no-cache'
      opt << "--build-arg PRODUCT=\"${product}\""
      opt << "--build-arg TAG=\"${tag}\""
      opt << '.'

      image = docker.build("${hubRepo}", opt.join(' '))
    }

    stage('push') {
      if (!noPush) {
        docker.withRegistry(
          'https://index.docker.io/v1/',
          'dockerhub-sqreadmin'
        ) {
          image.push(hubTag)
        }
      }
    } // push
  } // run

  node('docker') {
    timeout(time: timelimit, unit: 'HOURS') {
      run()
    }
  }
} // notify.wrap
