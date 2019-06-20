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
  def retries = 3

  def run = {
    def tasks = [:]

    triggerJob trigger: tasks,
      name: 'sqre/infra/build-layercake',
      parameters: [
        booleanParam(name: 'NO_PUSH', value: false),
      ]

    triggerJob trigger: tasks,
      name: 'sqre/infra/build-awscli',
      parameters: [
        booleanParam(name: 'NO_PUSH', value: false),
        booleanParam(name: 'LATEST', value: true),
      ]

    triggerJob trigger: tasks,
      name: 'sqre/infra/build-gitlfs-server',
      parameters: [
        booleanParam(name: 'NO_PUSH', value: false),
        booleanParam(name: 'LATEST', value: true),
      ]

    triggerJob trigger: tasks,
      name: 'sqre/infra/build-s3cmd',
      parameters: [
        booleanParam(name: 'NO_PUSH', value: false),
        booleanParam(name: 'LATEST', value: true),
      ]

    triggerJob trigger: tasks,
      name: 'sqre/backup/build-s3backup'

    triggerJob trigger: tasks,
      name: 'sqre/infra/build-ltd-mason',
      parameters: [
        booleanParam(name: 'NO_PUSH', value: false),
        booleanParam(name: 'LATEST', value: true),
      ]

    triggerJob trigger: tasks,
      name: 'sqre/infra/build-wget'

    triggerJob trigger: tasks,
      name: 'sqre/infra/build-cmirror'

    triggerJob trigger: tasks,
      name: 'sqre/backup/build-sqre-github-snapshot',
      parameters: [
        booleanParam(name: 'NO_PUSH', value: false),
        booleanParam(name: 'LATEST', value: true),
      ]

    triggerJob trigger: tasks,
      name: 'sqre/infra/build-gitlfs',
      parameters: [
        booleanParam(name: 'NO_PUSH', value: false),
        booleanParam(name: 'LATEST', value: true),
      ]

    triggerJob trigger: tasks,
      name: 'sqre/infra/build-codekit',
      parameters: [
        booleanParam(name: 'NO_PUSH', value: false),
        booleanParam(name: 'LATEST', value: true),
      ]

    triggerJob trigger: tasks,
      name: 'sqre/infra/build-nginx-ssl-proxy',
      parameters: [
        booleanParam(name: 'PUSH', value: true),
      ]

    triggerJob trigger: tasks,
      name: 'sqre/infra/build-postqa',
      parameters: [
        booleanParam(name: 'NO_PUSH', value: false),
      ]

    triggerJob trigger: tasks,
      name: 'sqre/infra/build-tag-monger',
      parameters: [
        booleanParam(name: 'NO_PUSH', value: false),
        booleanParam(name: 'LATEST', value: true),
      ]

    triggerJob trigger: tasks,
      name: 'sqre/infra/build-s3sync',
      parameters: [
        booleanParam(name: 'PUBLISH', value: true),
        booleanParam(name: 'LATEST', value: true),
      ]

    triggerJob trigger: tasks,
      name: 'sqre/backup/build-mysqldump-to-s3'

    triggerJob trigger: tasks,
      name: 'sqre/infra/build-dind',
      parameters: [
        booleanParam(name: 'NO_PUSH', value: false),
        booleanParam(name: 'LATEST', value: true),
      ]

    triggerJob trigger: tasks,
      name: 'sqre/infra/build-jenkins-swarm-client',
      parameters: [
        booleanParam(name: 'NO_PUSH', value: false),
        booleanParam(name: 'LATEST', value: true),
      ]

    triggerJob trigger: tasks,
      name: 'sqre/infra/build-docker-gc',
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
