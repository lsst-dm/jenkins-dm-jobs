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
  def branch  = ''
  def product = 'qserv_distrib'

  def bx = null
  def rebuildId = null
  def buildJob = 'release/run-rebuild'
  def publishJob = 'release/run-publish'

  stage('build') {
    def result = build job: buildJob,
        parameters: [
          string(name: 'BRANCH', value: branch),
          string(name: 'PRODUCT', value: product),
          booleanParam(name: 'SKIP_DEMO', value: true),
          booleanParam(name: 'SKIP_DOCS', value: true)
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
      bx = util.bxxxx(manifest)

      echo "parsed bxxxx: ${bx}"
    }
  }

  tagProduct(bx, 'qserv_latest', 'qserv_distrib', publishJob)

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
} // notify.wrap

def tagProduct(String buildId, String eupsTag, String product,
               String publishJob = 'release/run-publish') {
  stage("eups publish [${eupsTag}]") {
    build job: publishJob,
      parameters: [
        string(name: 'EUPSPKG_SOURCE', value: 'git'),
        string(name: 'BUILD_ID', value: buildId),
        string(name: 'TAG', value: eupsTag),
        string(name: 'PRODUCT', value: product)
      ]
  }
}

@NonCPS
def dumpJson(String filename, Map data) {
  def json = new groovy.json.JsonBuilder(data)
  def pretty = groovy.json.JsonOutput.prettyPrint(json.toString())
  echo pretty
  writeFile file: filename, text: pretty
}
