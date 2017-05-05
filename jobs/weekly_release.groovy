import util.Common
Common.makeFolders(this)

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

  // disabled cron trigger to move weekly tag from monday morning -> fridays
  /*
  triggers {
    cron('0 0 * * 5')
  }
  */

  def repo = SEED_JOB.scm.userRemoteConfigs.get(0).getUrl()
  def ref  = SEED_JOB.scm.getBranches().get(0).getName()

  definition {
    cpsScm {
      scm {
        git {
          remote {
            url(repo)
          }
          branch(ref)
        }
      }
      scriptPath('pipelines/release/weekly_release.groovy')
    }
  }
}
