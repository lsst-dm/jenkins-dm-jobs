def notify = null
node {
  dir('jenkins-dm-jobs') {
    // XXX the git step seemed to blowup on a branch of '*/<foo>'
    checkout([
      $class: 'GitSCM',
      branches: scm.getBranches(),
      userRemoteConfigs: scm.getUserRemoteConfigs()
    ])
    notify = load 'pipelines/lib/notify.groovy'
  }
}

try {
  notify.started()

  def git_tag = null
  def eups_tag = null
  def product = 'lsst_distrib qserv_distrib'
  def retries = 3
  def bx = null
  def rebuildId = null
  def buildJob = 'release/build-publish-tag'
  def publishJob = 'release/run-publish'
  def year = null
  def week = null

  stage('generate weekly tag') {
    def tz = TimeZone.getTimeZone('America/Los_Angeles')
    def dateFormat = new java.text.SimpleDateFormat('Y.w')
    dateFormat.setTimeZone(tz)
    def date = new java.util.Date()
    def dateStamp = dateFormat.format(date)

    git_tag = "w.${dateStamp}"
    echo "generated [git] tag: ${git_tag}"

    // eups doesn't like dots in tags, convert to underscores
    eups_tag = git_tag.tr('.-', '_')
    echo "generated [eups] tag: ${eups_tag}"

    year = new java.text.SimpleDateFormat('Y').setTimeZone(tz).format(date)
    week = new java.text.SimpleDateFormat('w').setTimeZone(tz).format(date)
  }

  stage('run build-publish-tag') {
    retry(retries) {
      def result = build job: buildJob,
        parameters: [
          string(name: 'BRANCH', value: ''),
          string(name: 'PRODUCT', value: product),
          string(name: 'GIT_TAG', value: git_tag),
          booleanParam(name: 'SKIP_DEMO', value: false),
          booleanParam(name: 'SKIP_DOCS', value: true)
        ]
      rebuildId = result.id
    }
  }

  stage('parse bNNNN') {
    node {
      step ([$class: 'CopyArtifact',
            projectName: buildJob,
            filter: 'results.json',
            selector: [$class: 'SpecificBuildSelector', buildNumber: rebuildId]
            ]);

      def results = slurpJson(readFile('results.json'))
      bx = results['bnnnn']

      echo "parsed bnnnn: ${bx}"
    }
  }

  tagProduct(bx, 'w_latest', 'lsst_distrib', publishJob)
  tagProduct(bx, 'qserv_latest', 'qserv_distrib', publishJob)
  tagProduct(bx, 'qserv-dev', 'qserv_distrib', publishJob)

  stage('build binary artifacts') {
    def artifact = [:]

    // docker containers
    artifact['run release/docker/build'] = {
      // ensure that we are using the latest version of newinstall.sh
      // this acts as an "after the fact" canary
      // XXX this job needs to be refactored to have proper canary builds
      // before the git/eups tags are published.
      retry(retries) {
        build job: 'release/docker/newinstall'
      }

      retry(retries) {
        build job: 'release/docker/build',
          parameters: [
            string(name: 'PRODUCTS', value: 'lsst_distrib'),
            string(name: 'TAG', value: eups_tag)
          ]
      }
    }

    artifact['run qserv/docker/build'] = {
      retry(retries) {
        build job: 'qserv/docker/build'
      }
    }

    artifact['run release/tarball'] = {
      retry(retries) {
        build job: 'release/tarball',
          parameters: [
            string(name: 'PRODUCT', value: 'lsst_distrib'),
            string(name: 'EUPS_TAG', value: eups_tag),
            booleanParam(name: 'SMOKE', value: true),
            booleanParam(name: 'RUN_DEMO', value: true),
            booleanParam(name: 'PUBLISH', value: true)
          ]
      }
    }

    parallel artifact
  }

  stage('build jupyterlabdemo image') {
    retry(retries) {
      build job: 'sqre/infrastructure/build-jupyterlabdemo',
        parameters: [
          choiceParam(name: 'BTYPE', value: 'w')
          stringParam(name: 'YEAR', value: year)
          stringParam(name: 'WEEK', value: week)
          choiceParam(name: 'PYVER', value: '3')
        ]
    }
  }

  stage('archive') {
    node {
      results = [
        bnnnn: bx,
        git_tag: git_tag,
        eups_tag: eups_tag
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

@NonCPS
def slurpJson(String data) {
  def slurper = new groovy.json.JsonSlurper()
  slurper.parseText(data)
}
