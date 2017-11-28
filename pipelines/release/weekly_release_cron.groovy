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
  def weeklyJob = 'release/weekly-release'
  def year = null
  def week = null

  stage('generate temporal coordinates') {
    def tz = TimeZone.getTimeZone('America/Los_Angeles')
    def date = new java.util.Date()

    def yearFormat = new java.text.SimpleDateFormat('YYYY')
    yearFormat.setTimeZone(tz)
    def weekFormat = new java.text.SimpleDateFormat('ww')
    weekFormat.setTimeZone(tz)

    year = yearFormat.format(date)
    week = weekFormat.format(date)

    echo "generated year: ${year}"
    echo "generated week: ${week}"
  }

  stage('run weekly-release') {
    build job: weeklyJob,
      parameters: [
        stringParam(name: 'YEAR', value: year),
        stringParam(name: 'WEEK', value: week)
      ]
  }
} // notify.wrap
