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
  echo "branch: ${params.BRANCH}"
  echo "product: ${params.PRODUCT}"
  echo "skip demo: ${params.SKIP_DEMO}"
  echo "skip docs: ${params.SKIP_DOCS}"
  echo "[eups] tag: ${params.EUPS_TAG}"

  def bx = null
  def retries = 3
  def buildJob = 'release/run-rebuild'
  def publishJob = 'release/run-publish'

  retry(retries) {
    bx = util.runRebuild(buildJob, [
      BRANCH: params.BRANCH,
      PRODUCT: params.PRODUCT,
      SKIP_DEMO: params.SKIP_DEMO.toBoolean(),
      SKIP_DOCS: params.SKIP_DOCS.toBoolean(),
    ])
  }

  stage('eups publish [tag]') {
    build job: publishJob,
      parameters: [
        string(name: 'EUPSPKG_SOURCE', value: 'git'),
        string(name: 'BUILD_ID', value: bx),
        string(name: 'TAG', value: params.EUPS_TAG),
        string(name: 'PRODUCT', value: params.PRODUCT)
      ]
  }

  stage('archive') {
    util.nodeTiny {
      results = [
        bnnnn: bx
      ]
      dumpJson('results.json', results)

      archiveArtifacts([
        artifacts: 'results.json',
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
