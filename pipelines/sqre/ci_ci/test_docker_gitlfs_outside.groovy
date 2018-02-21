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
    deleteDir()

    def hubRepo = 'lsstsqre/gitlfs'
    def local = "${hubRepo}-local"

    def gitRepo = 'https://github.com/lsst/validation_data_cfht.git'
    def gitRef = 'master'

    util.wrapContainer(hubRepo, local)

    def image = docker.image(local)

    dir('validation_data_cfht') {
      git([
        url: gitRepo,
        branch: gitRef,
        changelog: false,
        poll: false
      ])

      image.inside {
        util.bash('git lfs pull origin')
      }
    }

    util.bash('ls -la')
  } // node
} // notify.wrap
