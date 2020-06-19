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
    scipipe = util.scipipeConfig() // needed for side effects
    sqre = util.sqreConfig() // needed for side effects
  }
}

notify.wrap {
  def retries = 3

  def run = {
    def tasks = [:]

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
