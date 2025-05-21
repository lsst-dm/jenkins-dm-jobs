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
  def retries = 3

  def run = {
    def tasks = [:]

    triggerJob trigger: tasks,
      name: 'sqre/infra/build-s3cmd',
      parameters: [
        booleanParam(name: 'NO_PUSH', value: false),
        booleanParam(name: 'LATEST', value: true),
      ]

    triggerJob trigger: tasks,
      name: 'sqre/backup/build-ec2-snapshot',
      parameters: [
        booleanParam(name: 'NO_PUSH', value: false),
        booleanParam(name: 'LATEST', value: true),
      ]

    triggerJob trigger: tasks,
      name: 'sqre/backup/build-s3backup'

    triggerJob trigger: tasks,
      name: 'sqre/backup/build-sqre-github-snapshot',
      parameters: [
        booleanParam(name: 'NO_PUSH', value: false),
        booleanParam(name: 'LATEST', value: true),
      ]

    triggerJob trigger: tasks,
      name: 'sqre/infra/build-postqa',
      parameters: [
        booleanParam(name: 'NO_PUSH', value: false),
      ]

    triggerJob trigger: tasks,
      name: 'sqre/infra/build-jenkins-swarm-client',
      parameters: [
        booleanParam(name: 'NO_PUSH', value: false),
        booleanParam(name: 'LATEST', value: true),
      ]

    parallel tasks
  } // run

  timeout(time: 24, unit: 'HOURS') {
    stage('trigger') {
      run()
    } // stage
  } // timeout
} // notify.wrap

def triggerJob(Map args) {
  args.trigger[args.name] = {
    retry(args.retries) {
      build job: args.name,
        parameters: args.parameters
    }
  }
}
