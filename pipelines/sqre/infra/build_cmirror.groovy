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
  def hub_repo = 'lsstsqre/cmirror'

  def image = null

  def run = {
    stage('checkout') {
      git([
        url: 'https://github.com/lsst-sqre/sandbox-cmirror',
        branch: 'master'
      ])
    }

    stage('build') {
      docker.image('centos:7').pull()

      dir('cmirror') {
        image = docker.build(hub_repo, '--no-cache .')
      }
    }

    stage('push') {
      docker.withRegistry('https://index.docker.io/v1/', 'dockerhub-sqreadmin') {
        image.push('latest')
      }
    }
  } // run

  util.nodeWrap('docker') {
    timeout(time: 30, unit: 'MINUTES') {
      run()
    }
  }
} // notify.wrap
