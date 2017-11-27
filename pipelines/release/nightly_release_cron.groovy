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
  def releaseJob = 'release/nightly-release'
  def year = null
  def month = null
  def day = null

  stage('generate temporal coordinates') {
    def tz = TimeZone.getTimeZone('America/Los_Angeles')
    def date = new java.util.Date()

    def yearFormat = new java.text.SimpleDateFormat('YYYY')
    yearFormat.setTimeZone(tz)
    def monthFormat = new java.text.SimpleDateFormat('MM')
    monthFormat.setTimeZone(tz)
    def dayFormat = new java.text.SimpleDateFormat('dd')
    dayFormat.setTimeZone(tz)

    year = yearFormat.format(date)
    month = monthFormat.format(date)
    day = dayFormat.format(date)

    echo "generated year: ${year}"
    echo "generated month: ${month}"
    echo "generated day: ${day}"
  }

  stage('run nightly-release') {
    build job: releaseJob,
      parameters: [
        stringParam(name: 'YEAR', value: year),
        stringParam(name: 'MONTH', value: month),
        stringParam(name: 'DAY', value: day)
      ]
  }
} // notify.wrap
