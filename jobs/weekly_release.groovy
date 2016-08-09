pipelineJob('release/weekly-release') {
  properties {
    rebuild {
      autoRebuild()
    }
  }

  // don't tie up a beefy build slave
  label('jenkins-master')
  concurrentBuild(false)
  keepDependencies(true)

  triggers {
    cron('0 0 * * 1')
  }

  definition {
    cpsScm {
      scm {
        github('lsst-sqre/jenkins-dm-jobs', '*/master')
      }
      scriptPath('pipelines/weekly_release.groovy')
    }
  }
}
