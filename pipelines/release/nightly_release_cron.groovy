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

  def triggerJob = 'release/nightly-release'
  def year = null
  def month = null
  def day = null

  stage('generate temporal coordinates') {
    def tz = TimeZone.getTimeZone('America/Los_Angeles')
    def date = new java.util.Date()

    def yearFormat = new java.text.SimpleDateFormat('Y')
    yearFormat.setTimeZone(tz)
    def monthFormat = new java.text.SimpleDateFormat('M')
    monthFormat.setTimeZone(tz)
    def dayFormat = new java.text.SimpleDateFormat('d')
    dayFormat.setTimeZone(tz)

    year = yearFormat.format(date)
    month = monthFormat.format(date)
    day = dayFormat.format(date)

    echo "generated year: ${year}"
    echo "generated month: ${month}"
    echo "generated day: ${day}"
  }

  stage('run nightly-release') {
    build job: triggeredJob,
      parameters: [
        stringParam(name: 'YEAR', value: year),
        stringParam(name: 'MONTH', value: month)
        stringParam(name: 'DAY', value: day)
      ]
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
