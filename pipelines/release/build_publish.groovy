def notify = null

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
  }
}

try {
  notify.started()

  echo "branch: ${BRANCH}"
  echo "product: ${PRODUCT}"
  echo "skip demo: ${SKIP_DEMO}"
  echo "skip docs: ${SKIP_DOCS}"
  echo "[eups] tag: ${EUPS_TAG}"

  def bx = null
  def rebuildId = null
  def buildJob = 'release/run-rebuild'
  def publishJob = 'release/run-publish'

  stage('build') {
    def result = build job: buildJob,
        parameters: [
          string(name: 'BRANCH', value: BRANCH),
          string(name: 'PRODUCT', value: PRODUCT),
          booleanParam(name: 'SKIP_DEMO', value: SKIP_DEMO.toBoolean()),
          booleanParam(name: 'SKIP_DOCS', value: SKIP_DOCS.toBoolean())
        ],
        wait: true
    rebuildId = result.id
  }

  stage('parse bNNNN') {
    node {
      manifest_artifact = 'lsstsw/build/manifest.txt'

      step ([$class: 'CopyArtifact',
            projectName: buildJob,
            filter: manifest_artifact,
            selector: [$class: 'SpecificBuildSelector', buildNumber: rebuildId]
            ]);

      def manifest = readFile manifest_artifact
      bx = bxxxx(manifest)

      echo "parsed bxxxx: ${bx}"
    }
  }

  stage('eups publish [tag]') {
    build job: publishJob,
      parameters: [
        string(name: 'EUPSPKG_SOURCE', value: 'git'),
        string(name: 'BUILD_ID', value: bx),
        string(name: 'TAG', value: EUPS_TAG),
        string(name: 'PRODUCT', value: PRODUCT)
      ]
  }

  stage('archive') {
    node {
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
} catch (e) {
  // If there was an exception thrown, the build failed
  currentBuild.result = "FAILED"
  throw e
} finally {
  echo "result: ${currentBuild.result}"
  switch(currentBuild.result) {
    case null:
    case 'SUCCESS':
      notify.success()
      break
    case 'ABORTED':
      notify.aborted()
      break
    case 'FAILURE':
      notify.failure()
      break
    default:
      notify.failure()
  }
}

@NonCPS
def bxxxx(manifest) {
  def m = manifest =~ /(?m)^BUILD=(b.*)/
  m ? m[0][1] : null
}

@NonCPS
def dumpJson(String filename, Map data) {
  def json = new groovy.json.JsonBuilder(data)
  def pretty = groovy.json.JsonOutput.prettyPrint(json.toString())
  echo pretty
  writeFile file: filename, text: pretty
}
