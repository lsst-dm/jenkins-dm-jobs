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

  def retries    = 3
  def buildJob   = 'release/run-rebuild'
  def publishJob = 'release/run-publish'

  def manifestId = null

  retry(retries) {
    manifestId = util.runRebuild(buildJob, [
      BRANCH: branch,
      PRODUCT: product,
      SKIP_DEMO: skipDemo,
      SKIP_DOCS: skipDocs,
    ])
  }

  stage('eups publish [tag]') {
    retry(retries) {
      util.runPublish(manifestId, eupsTag, product, 'git', publishJob)
    }
  }

  stage('archive') {
    def resultsFile = 'results.json'

    util.nodeTiny {
      results = [:]
      if (manifestId) {
        results['manifest_id'] = manifestId
      }
      if (gitTag) {
        results['git_tag'] = gitTag
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
} // notify.wrap

@NonCPS
def dumpJson(String filename, Map data) {
  def json = new groovy.json.JsonBuilder(data)
  def pretty = groovy.json.JsonOutput.prettyPrint(json.toString())
  echo pretty
  writeFile file: filename, text: pretty
}
