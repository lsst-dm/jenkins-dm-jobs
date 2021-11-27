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
  def hub_repo = 'lsstsqre/ec2-snapshot'

  def image = null

  def run = {
    stage('pull') {
      image = docker.image("${hub_repo}:latest")
      image.pull()
    }

    withCredentials([[
      $class: 'UsernamePasswordMultiBinding',
      credentialsId: 'aws-jenkins-master-snapshot',
      usernameVariable: 'AWS_ACCESS_KEY_ID',
      passwordVariable: 'AWS_SECRET_ACCESS_KEY'
    ],
    [
      $class: 'StringBinding',
      credentialsId: 'jenkins-env',
      variable: 'JENKINS_ENV',
    ]]) {
      stage('snapshot') {
        withEnv([
          "CUSTOM_TAGS=Key=jenkins_env,Value=${env.JENKINS_ENV}",
        ]) {
          // #inside is only being used to map env vars into the container
          image.inside {
            util.bash '/usr/local/bin/ec2-snapshot.sh'
          }
        } // withEnv
      } // stage
    } // withCredentials
  } // run

  node('jenkins-manager') {
    timeout(time: 1, unit: 'HOURS') {
      run()
    }
  }
} // notify.wrap
