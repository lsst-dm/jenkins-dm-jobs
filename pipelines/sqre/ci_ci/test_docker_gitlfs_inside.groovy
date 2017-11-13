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
  node('docker') {
    def hubRepo = 'docker.io/lsstsqre/gitlfs'
    def local = "${hubRepo}-local"

    util.wrapContainer(hubRepo, local)

    def image = docker.image(local)

    image.inside {
      util.shColor '''
        whereis git git-lfs
        git --version
        git lfs version
      '''

      //util.shColor 'git lfs clone https://github.com/lsst/validation_data_cfht.git'

      checkout([$class: 'GitSCM', branches: [[name: '*/master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'ci_hsc'], [$class: 'GitLFSPull']], submoduleCfg: [], userRemoteConfigs: [[url: 'https://github.com/lsst/ci_hsc.git']]])

      /*
      dir('ci_hsc') {
        checkout([
          scm: [
            $class: 'GitSCM',
            branches: [[name: 'master']],
            extensions: [[$class: 'GitLFSPull']],
            userRemoteConfigs: [
              [url: 'https://github.com/lsst/ci_hsc.git'],
            ],
          ],
          changelog: false,
          poll: false,
        ])
      }
      */
    } // inside

    util.shColor('ls -la')
  } // node
} // notify.wrap
