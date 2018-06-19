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
  util.requireParams([
    'BRANCH',
    'EUPS_TAG',
    'PRODUCT',
    'SKIP_DEMO',
    'SKIP_DOCS',
  ])

  String branch    = params.BRANCH
  String eupsTag   = params.EUPS_TAG
  String product   = params.PRODUCT
  Boolean skipDemo = params.SKIP_DEMO
  Boolean skipDocs = params.SKIP_DOCS

  echo "branch: ${branch}"
  echo "[eups] tag: ${eupsTag}"
  echo "product: ${product}"
  echo "skip demo: ${skipDemo}"
  echo "skip docs: ${skipDocs}"

  def retries = 3

  def manifestId = null

  def run = {
    stage('build') {
      retry(retries) {
        manifestId = util.runRebuild(
          parameters: [
            BRANCH: branch,
            PRODUCT: product,
            SKIP_DEMO: skipDemo,
            SKIP_DOCS: skipDocs,
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
            PRODUCT: product,
          ],
        )
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
