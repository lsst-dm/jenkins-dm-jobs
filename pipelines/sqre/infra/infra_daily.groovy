node('jenkins-manager') {
  dir('jenkins-dm-jobs') {
    checkout([
      $class: 'GitSCM',
      branches: scm.getBranches(),
      userRemoteConfigs: scm.getUserRemoteConfigs(),
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
    parallel(
      'tag-monger': {
        triggerJob name: 'sqre/infra/tag-monger', retries: retries
      },
      // newinstall FROMs the scipipe base image, so the two run in sequence;
      // a base-image failure skips newinstall rather than building it on a
      // stale base.
      'images': {
        triggerJob name: 'sqre/infra/build-docker-scipipe-base', retries: retries
        triggerJob name: 'sqre/infra/build_docker_newinstall', retries: retries
      },
    )
  } // run

  timeout(time: 24, unit: 'HOURS') {
    stage('trigger') {
      run()
    } // stage
  } // timeout
} // notify.wrap

def triggerJob(Map args) {
  retry(args.retries) {
    build job: args.name,
      parameters: args.parameters
  }
}
