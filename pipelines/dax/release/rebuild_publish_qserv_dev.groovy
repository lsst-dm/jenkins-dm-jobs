def config = null

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
    config = util.readYamlFile 'etc/science_pipelines/build_matrix.yaml'
  }
}

notify.wrap {
  def product         = 'qserv_distrib'
  def tarballProducts = product
  def retries         = 3
  def buildJob        = 'release/run-rebuild'
  def publishJob      = 'release/run-publish'
  def eupsTag         = 'qserv-dev'

  def manifestId = null

  try {
    timeout(time: 30, unit: 'HOURS') {
      stage('build') {
        retry(retries) {
          manifestId = util.runRebuild(buildJob, [
            PRODUCT: product,
            SKIP_DEMO: true,
            SKIP_DOCS: true,
            TIMEOUT: '8', // hours
          ])
        }
      }

      stage('eups publish') {
        def pub = [:]

        pub[eupsTag] = {
          retry(retries) {
            util.runPublish(manifestId, eupsTag, product, 'git', publishJob)
          }
        }

        parallel pub
      }
    } // timeout
  } finally {
    stage('archive') {
      def resultsFile = 'results.json'

      util.nodeTiny {
        results = [:]
        if (manifestId) {
          results['manifest_id'] = manifestId
        }
        if (eupsTag) {
          results['eups_tag'] = eupsTag
        }

        util.dumpJson(resultsFile, results)

        archiveArtifacts([
          artifacts: resultsFile,
          fingerprint: true
        ])
      }
    }
  } // try
} // notify.wrap
