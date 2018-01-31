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
  def image = null
  def hub_repo = 'lsstsqre/ec2-snapshot'

  def run = {
    stage('pull') {
      image = docker.image("${hub_repo}:latest")
      image.pull()
    }

    withCredentials([[
      $class: 'UsernamePasswordMultiBinding',
      credentialsId: 'jenkins-aws',
      usernameVariable: 'AWS_ACCESS_KEY_ID',
      passwordVariable: 'AWS_SECRET_ACCESS_KEY'
    ]]) {
      stage('snapshot') {
        image.inside {
          util.bash '/usr/local/bin/ec2-snapshot.sh'
        }
      } // stage
    } // withCredentials
  } // run

  node('docker') {
    timeout(time: 1, unit: 'HOURS') {
      run()
    }
  }
} // notify.wrap
