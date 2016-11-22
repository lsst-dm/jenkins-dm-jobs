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
  }

  stage('run build-publish-tag') {
    retry(3) {
      build job: 'release/build-publish-tag',
        parameters: [
          string(name: 'BRANCH', value: ''),
          string(name: 'PRODUCT', value: product),
          string(name: 'GIT_TAG', value: git_tag),
          booleanParam(name: 'SKIP_DEMO', value: false),
          booleanParam(name: 'SKIP_DOCS', value: false)
        ]
    }
  }

  stage('run docker/build') {
    retry(3) {
      build job: 'release/docker/build',
        parameters: [
          string(name: 'PRODUCT', value: product),
          string(name: 'TAG', value: eups_tag),
        ]
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
