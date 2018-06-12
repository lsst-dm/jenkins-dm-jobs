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
    config = util.scipipeConfig()
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

  def run = {
    stage('build') {
      retry(retries) {
        manifestId = util.runRebuild(buildJob, [
          PRODUCT: product,
          SKIP_DEMO: true,
          SKIP_DOCS: true,
          TIMEOUT: '8', // hours
        ])
      } // retry
    } // stage

    stage('eups publish') {
      retry(retries) {
        util.runPublish(manifestId, eupsTag, product, 'git', publishJob)
      }
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
          git_tag: gitTag ?: null,
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
