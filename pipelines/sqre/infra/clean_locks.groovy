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
  util.requireParams([
    'AGENT_NUM',
    'PLATFORM_HASH',
    'ENV_HASH'
  ])
  node("agent-ldfc-${params.AGENT_NUM}") {
    dir("/j/ws/stack-os-matrix/${params.PLATFORM_HASH}/lsstsw/stack/${params.ENV_HASH}/.lockDir") {
       deleteDir()
    }
  }
}
