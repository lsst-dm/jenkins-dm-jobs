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
    dockerfile = readFile('pipelines/sqre/ci_ci/test_docker_from_arg.Dockerfile')
  }
}

notify.wrap {
  util.nodeWrap('docker') {
    writeFile(file: 'Dockerfile', text: dockerfile)

    // testing that jenkins docker support doesn't blow up with an ARG used in
    // the FROM
    image = docker.build('fromtest:fromtag', '--build-arg base="centos:7" .')
  }
}
