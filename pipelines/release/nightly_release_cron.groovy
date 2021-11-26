import java.time.LocalDate
import java.time.ZoneId

node('jenkins-manager') {
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
    scipipe = util.scipipeConfig() // needed for side effects
    sqre = util.sqreConfig() // needed for side effects
  }
}

notify.wrap {
  def releaseJob = 'release/nightly-release'

  def year  = null
  def month = null
  def day   = null

  stage('generate temporal coordinates') {
    def date = LocalDate.now(ZoneId.of('America/Los_Angeles'))

    year  = date.getYear().toString()
    month = date.getMonthValue().toString()
    day   = date.getDayOfMonth().toString()

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
