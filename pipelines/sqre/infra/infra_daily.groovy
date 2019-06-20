node {
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
      name: 'sqre/infra/tag-monger'

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
