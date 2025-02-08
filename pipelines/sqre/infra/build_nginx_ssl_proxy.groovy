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
    'PUSH',
  ])

  Boolean pushDocker = params.PUSH

  def image = null
  def hub_repo = 'lsstsqre/nginx-ssl-proxy'

  def run = {
    stage('checkout') {
      git([
        url: 'https://github.com/lsst-sqre/nginx-ssl-proxy',
        branch: 'master'
      ])
    }

    stage('build') {
      image = docker.build("${hub_repo}", '--no-cache --pull=true .')
    }

    if (pushDocker) {
      stage('push') {
        docker.withRegistry('https://index.docker.io/v1/', 'dockerhub-sqreadmin') {
          image.push('latest')
        }
      }
    }
  } // run

  util.nodeWrap('linux-64') {
    timeout(time: 30, unit: 'MINUTES') {
      run()
    }
  }
} // notify.wrap
