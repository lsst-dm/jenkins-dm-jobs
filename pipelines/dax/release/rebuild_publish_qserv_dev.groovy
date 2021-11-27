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
  def products        = 'qserv_distrib'
  def tarballProducts = products
  def retries         = 3
  def eupsTag         = 'qserv-dev'

  def manifestId = null
  def splenvRef = scipipe['dax-lsstsw-matrix'][0].splenv_ref

  def run = {
    stage('build') {
      retry(retries) {
        manifestId = util.runRebuild(
          parameters: [
            PRODUCTS: products,
            BUILD_DOCS: false,
            SPLENV_REF: splenvRef,
          ],
        )
      } // retry
    } // stage

    stage('eups publish') {
      retry(retries) {
        util.runPublish(
          parameters: [
            EUPSPKG_SOURCE: 'git',
            MANIFEST_ID: manifestId,
            EUPS_TAG: eupsTag,
            PRODUCTS: products,
            SPLENV_REF: splenvRef,
          ],
        )
      } // retry
    } // stage
  } // run

  try {
    timeout(time: 30, unit: 'HOURS') {
      run()
    }
  } finally {
    stage('archive') {
      def resultsFile = 'results.json'

      util.nodeTiny {
        util.dumpJson(resultsFile, [
          manifest_id: manifestId ?: null,
          eups_tag: eupsTag ?: null,
        ])

        archiveArtifacts([
          artifacts: resultsFile,
          fingerprint: true
        ])
      }
    } // stage
  } // try
} // notify.wrap
