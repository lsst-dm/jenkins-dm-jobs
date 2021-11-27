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
  util.nodeWrap('docker') {
    deleteDir()

    def hubRepo = 'lsstsqre/gitlfs'
    def local = "${hubRepo}-local"

    def gitRepo = util.githubSlugToUrl('lsst/validation_data_cfht')
    def gitRef = 'main'

    util.wrapDockerImage(
      image: hubRepo,
      tag: local,
      pull: true,
    )

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
  } // util.nodeWrap
} // notify.wrap
