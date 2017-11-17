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
    def gitRepo = 'https://github.com/lsst/validation_data_cfht'
    def gitRef  = 'master'
    def runs    = 5
    def repoDir = 'validation_data_cfg'

    try {
      ['1.5.5', '2.3.4'].each { lfsVer ->
        def hub = "docker.io/lsstsqre/gitlfs:${lfsVer}"
        def local = "${hub}-local"

        util.wrapContainer(hub, local)
        def image = docker.image(local)

        runs.times {
          dir(repoDir) {
            git([
              url: gitRepo,
              branch: gitRef,
              changelog: false,
              poll: false
            ])

            image.inside {
              util.shColor """
                /usr/bin/time \
                  --format='%e' \
                  --output=\${WORKSPACE}/lfspull-${lfsVer}.txt \
                  --append \
                  git lfs pull origin
              """
            }

            // cleanup before next iteration
            deleteDir()
          } // dir
        } // times
      } // each
    } finally {
      archiveArtifacts([
        artifacts: "**/lfspull*.txt",
      ])
      deleteDir()
    }
  } // node
} // notify.wrap
