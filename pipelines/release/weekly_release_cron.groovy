import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

node {
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
    def date = LocalDate.now(ZoneId.of('America/Los_Angeles'))

    year = date.format(DateTimeFormatter.ofPattern('YYYY'))
    week = date.format(DateTimeFormatter.ofPattern('ww'))

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
